"""Feiyu Notes anonymous feedback receiver (plan 0.3.2, P2).

Python 3 standard library only: http.server + sqlite3. Runs as one process on the loopback
interface behind a reverse proxy. Request bodies are never logged.

    python server.py serve --db /var/lib/feiyu-feedback/feedback.db [--port 8787]
    python server.py purge --db ...            # delete reports older than the retention period
    python server.py show  --db ... REPORT_ID  # print one report as JSON
"""

import argparse
import contextlib
import hashlib
import json
import sqlite3
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PATH = "/api/v1/feedback"
MAX_BODY = 512 * 1024
MAX_DESCRIPTION = 4000
RETENTION_SECONDS = 30 * 24 * 3600
STORAGE_BUDGET = 256 * 1024 * 1024
RATE_LIMIT = 5  # per client IP per RATE_WINDOW
RATE_WINDOW = 60
MAX_CONNECTIONS = 16
READ_TIMEOUT = 10
TRUSTED_PROXIES = frozenset({"127.0.0.1", "::1"})

DIAGNOSTIC_KEYS = {"schemaVersion", "app", "device", "events", "crash", "truncated"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS reports (
    report_id     TEXT PRIMARY KEY,
    submission_id TEXT NOT NULL UNIQUE,
    content_hash  TEXT NOT NULL,
    description   TEXT NOT NULL,
    diagnostics   TEXT,
    size          INTEGER NOT NULL,
    received_at   INTEGER NOT NULL
)
"""


class Rejected(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def validate(payload):
    """Returns (submission_id, description, diagnostics_json_or_None) or raises Rejected(400)."""
    bad = Rejected(400, "invalid_request")
    if not isinstance(payload, dict) or set(payload) != {"schemaVersion", "submissionId", "description", "diagnostics"}:
        raise bad
    if not _is_int(payload["schemaVersion"]) or payload["schemaVersion"] != 1:
        raise Rejected(400, "unsupported_schema")
    try:
        submission_id = str(uuid.UUID(payload["submissionId"]))
    except (TypeError, ValueError, AttributeError):
        raise bad
    description = payload["description"]
    if not isinstance(description, str):
        raise bad
    description = description.strip()
    if not 1 <= len(description) <= MAX_DESCRIPTION:
        raise bad
    diagnostics = payload["diagnostics"]
    if diagnostics is not None:
        _validate_diagnostics(diagnostics)
        diagnostics = json.dumps(diagnostics, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    try:  # rejects lone surrogates that json.loads lets through
        description.encode("utf-8")
        if diagnostics is not None:
            diagnostics.encode("utf-8")
    except UnicodeEncodeError:
        raise bad
    return submission_id, description, diagnostics


def _validate_diagnostics(d):
    bad = Rejected(400, "invalid_diagnostics")
    if not isinstance(d, dict) or set(d) != DIAGNOSTIC_KEYS:
        raise bad
    if not _is_int(d["schemaVersion"]) or d["schemaVersion"] != 1:
        raise bad
    app, device = d["app"], d["device"]
    if not (isinstance(app, dict) and isinstance(app.get("versionName"), str) and _is_int(app.get("versionCode"))):
        raise bad
    if not (isinstance(device, dict) and isinstance(device.get("manufacturer"), str)
            and isinstance(device.get("model"), str) and _is_int(device.get("androidSdk"))):
        raise bad
    if not isinstance(d["events"], list) or not all(isinstance(e, dict) for e in d["events"]):
        raise bad
    if d["crash"] is not None and not isinstance(d["crash"], dict):
        raise bad
    if not isinstance(d["truncated"], bool):
        raise bad


class Store:
    def __init__(self, path, budget=STORAGE_BUDGET, clock=time.time):
        self.path = path
        self.budget = budget
        self.clock = clock
        with contextlib.closing(self._connect()) as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute(SCHEMA)

    def _connect(self):
        return sqlite3.connect(self.path, timeout=10, isolation_level=None)

    def submit(self, submission_id, description, diagnostics):
        """Returns (status, report_id). Commits before returning 201; never overwrites a report."""
        content_hash = hashlib.sha256(json.dumps([description, diagnostics]).encode("utf-8")).hexdigest()
        size = len(description.encode("utf-8")) + len((diagnostics or "").encode("utf-8"))
        db = None
        try:
            db = self._connect()
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT report_id, content_hash FROM reports WHERE submission_id = ?", (submission_id,)
            ).fetchone()
            if row:
                db.execute("ROLLBACK")
                if row[1] != content_hash:
                    raise Rejected(409, "submission_conflict")
                return 200, row[0]
            used = db.execute("SELECT COALESCE(SUM(size), 0) FROM reports").fetchone()[0]
            if used + size > self.budget:
                db.execute("ROLLBACK")
                raise Rejected(503, "storage_full")
            report_id = str(uuid.uuid4())
            db.execute(
                "INSERT INTO reports VALUES (?, ?, ?, ?, ?, ?, ?)",
                (report_id, submission_id, content_hash, description, diagnostics, size, int(self.clock())),
            )
            db.execute("COMMIT")
            return 201, report_id
        except sqlite3.Error:
            if db is not None and db.in_transaction:
                db.execute("ROLLBACK")
            raise Rejected(503, "unavailable")
        finally:
            if db is not None:
                db.close()

    def purge(self, retention=RETENTION_SECONDS):
        with contextlib.closing(self._connect()) as db:
            return db.execute("DELETE FROM reports WHERE received_at < ?", (int(self.clock()) - retention,)).rowcount

    def show(self, report_id):
        with contextlib.closing(self._connect()) as db:
            row = db.execute(
                "SELECT report_id, description, diagnostics, received_at FROM reports WHERE report_id = ?", (report_id,)
            ).fetchone()
        if not row:
            return None
        return {
            "reportId": row[0],
            "description": row[1],
            "diagnostics": json.loads(row[2]) if row[2] else None,
            "receivedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(row[3])),
        }


class RateLimiter:
    """In-memory sliding window; client IPs live only for one window."""

    def __init__(self, limit=RATE_LIMIT, window=RATE_WINDOW, clock=time.monotonic):
        self.limit, self.window, self.clock = limit, window, clock
        self.hits = {}
        self.lock = threading.Lock()

    def check(self, ip):
        """Returns 0 when allowed, else seconds until the next slot."""
        now = self.clock()
        with self.lock:
            for key in [k for k, v in self.hits.items() if v[-1] <= now - self.window]:
                del self.hits[key]
            recent = [t for t in self.hits.get(ip, []) if t > now - self.window]
            if len(recent) >= self.limit:
                self.hits[ip] = recent
                return max(1, int(recent[0] + self.window - now) + 1)
            self.hits[ip] = recent + [now]
            return 0


class Handler(BaseHTTPRequestHandler):
    timeout = READ_TIMEOUT
    server_version = "FeiyuFeedback"
    sys_version = ""

    def log_message(self, format, *args):  # no access log: never record paths, IPs or bodies
        pass

    def _reply(self, status, body, headers=()):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def _client_ip(self):
        peer = self.client_address[0]
        forwarded = self.headers.get("X-Forwarded-For")
        if peer in TRUSTED_PROXIES and forwarded:
            return forwarded.split(",")[-1].strip()
        return peer

    def do_GET(self):
        self._reply(405 if self.path == PATH else 404, {"error": "method_not_allowed" if self.path == PATH else "not_found"})

    do_PUT = do_DELETE = do_PATCH = do_GET

    def do_POST(self):
        if self.path != PATH:
            self.close_connection = True
            return self._reply(404, {"error": "not_found"})
        if not self.server.slots.acquire(blocking=False):
            self.close_connection = True
            return self._reply(503, {"error": "unavailable"})
        try:
            status, body, headers = self._handle()
        except Rejected as r:
            status, body, headers = r.status, {"error": r.code}, ()
        finally:
            self.server.slots.release()
        self.close_connection = True
        self._reply(status, body, headers)

    def _handle(self):
        wait = self.server.limiter.check(self._client_ip())
        if wait:
            return 429, {"error": "rate_limited"}, (("Retry-After", str(wait)),)
        media = self.headers.get("Content-Type", "").lower().replace(" ", "")
        if media not in ("application/json", "application/json;charset=utf-8"):
            raise Rejected(415, "unsupported_media_type")
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding"):
            raise Rejected(400, "invalid_request")
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise Rejected(400, "invalid_request")
        if length > MAX_BODY:
            raise Rejected(413, "too_large")
        if length < 0:
            raise Rejected(400, "invalid_request")
        try:
            raw = self.rfile.read(length)
        except OSError:  # includes the read timeout
            raise Rejected(400, "invalid_request")
        if len(raw) != length:
            raise Rejected(400, "invalid_request")
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise Rejected(400, "invalid_request")
        status, report_id = self.server.store.submit(*validate(payload))
        return status, {"reportId": report_id}, ()


def make_server(store, host="127.0.0.1", port=8787, limiter=None):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.store = store
    server.limiter = limiter or RateLimiter()
    server.slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
    return server


def main(argv=None):
    parser = argparse.ArgumentParser(description="Feiyu Notes feedback receiver")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("serve", "purge", "show"):
        p = sub.add_parser(name)
        p.add_argument("--db", required=True)
        if name == "serve":
            p.add_argument("--host", default="127.0.0.1")
            p.add_argument("--port", type=int, default=8787)
        if name == "show":
            p.add_argument("report_id")
    args = parser.parse_args(argv)
    store = Store(args.db)
    if args.command == "serve":
        make_server(store, args.host, args.port).serve_forever()
    elif args.command == "purge":
        print(f"deleted {store.purge()} expired report(s)")
    else:
        report = store.show(args.report_id)
        if report is None:
            print("not found", file=sys.stderr)
            return 1
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
