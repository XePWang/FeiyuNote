import contextlib
import http.client
import json
import os
import sqlite3
import tempfile
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from unittest import mock

import server

DIAGNOSTICS = {
    "schemaVersion": 1,
    "app": {"versionName": "0.3.2", "versionCode": 7},
    "device": {"manufacturer": "Synthetic", "model": "Test", "androidSdk": 36},
    "events": [{"time": "2026-10-01T00:00:00Z", "operation": "GENERATE", "result": "FAILED", "error": "NETWORK"}],
    "crash": None,
    "truncated": False,
}


def body(submission_id=None, description="合成问题描述", diagnostics=None, **extra):
    payload = {
        "schemaVersion": 1,
        "submissionId": submission_id or str(uuid.uuid4()),
        "description": description,
        "diagnostics": diagnostics,
    }
    payload.update(extra)
    return payload


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.dir.name, "feedback.db")
        self.now = [1_800_000_000.0]
        self.store = server.Store(self.db, clock=lambda: self.now[0])
        self.start(self.store)

    def start(self, store, limiter=None):
        self.server = server.make_server(store, port=0, limiter=limiter or server.RateLimiter(limit=1000))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def tearDown(self):
        self.stop()
        self.dir.cleanup()

    def post(self, payload=None, raw=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        data = raw if raw is not None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        h = {"Content-Type": "application/json; charset=utf-8"}
        h.update(headers or {})
        conn.request("POST", server.PATH, body=data, headers=h)
        response = conn.getresponse()
        result = response.status, json.loads(response.read()), dict(response.getheaders())
        conn.close()
        return result

    def rows(self):
        with contextlib.closing(sqlite3.connect(self.db)) as db:
            return db.execute("SELECT submission_id, description, diagnostics FROM reports").fetchall()

    def test_first_submission_is_stored_and_returns_report_id(self):
        status, reply, _ = self.post(body(description="  有问题  "))
        self.assertEqual(201, status)
        self.assertEqual(str(uuid.UUID(reply["reportId"])), reply["reportId"])
        self.assertEqual([("有问题", None)], [(r[1], r[2]) for r in self.rows()])

    def test_same_submission_returns_original_report(self):
        payload = body(diagnostics=DIAGNOSTICS)
        first = self.post(payload)
        second = self.post(payload)
        self.assertEqual((201, 200), (first[0], second[0]))
        self.assertEqual(first[1]["reportId"], second[1]["reportId"])
        self.assertEqual(1, len(self.rows()))

    def test_same_id_with_different_content_conflicts_without_overwrite(self):
        sid = str(uuid.uuid4())
        self.post(body(sid, "原描述"))
        status, reply, _ = self.post(body(sid, "改过的描述"))
        self.assertEqual((409, {"error": "submission_conflict"}), (status, reply))
        self.assertEqual("原描述", self.rows()[0][1])

    def test_concurrent_duplicates_create_one_report(self):
        payload = body()
        with ThreadPoolExecutor(8) as pool:
            results = list(pool.map(lambda _: self.post(payload), range(8)))
        self.assertEqual(1, sum(1 for r in results if r[0] == 201))
        self.assertEqual(1, len({r[1]["reportId"] for r in results}))
        self.assertEqual(1, len(self.rows()))

    def test_invalid_requests_are_rejected(self):
        cases = [
            body(schemaVersion=2),
            body(schemaVersion=True),
            body(submission_id="not-a-uuid"),
            body(description="   "),
            body(description="x" * 4001),
            body(diagnostics={"schemaVersion": 1}),
            body(diagnostics=dict(DIAGNOSTICS, extra=1)),
            body(extra="field"),
        ]
        for payload in cases:
            self.assertEqual(400, self.post(payload)[0], payload)
        self.assertEqual(400, self.post(raw=b"{not json")[0])
        self.assertEqual(400, self.post(raw=b'{"schemaVersion":1,"submissionId":"' + str(uuid.uuid4()).encode()
                                        + b'","description":"\\ud800","diagnostics":null}')[0])
        self.assertEqual(415, self.post(body(), headers={"Content-Type": "text/plain"})[0])
        self.assertEqual([], self.rows())

    def test_description_limit_counts_code_points(self):
        self.assertEqual(201, self.post(body(description="🐟" * 4000))[0])

    def test_oversized_body_is_rejected(self):
        # Declared length alone decides; the body is never read.
        status, reply, _ = self.post(raw=b"{}", headers={"Content-Length": str(server.MAX_BODY + 1)})
        self.assertEqual((413, {"error": "too_large"}), (status, reply))

    def test_retry_after_restart_returns_original_receipt(self):
        payload = body()
        first = self.post(payload)[1]["reportId"]
        self.stop()
        self.start(server.Store(self.db))
        status, reply, _ = self.post(payload)
        self.assertEqual((200, first), (status, reply["reportId"]))

    def test_storage_failure_never_reports_success(self):
        with mock.patch.object(self.store, "_connect", side_effect=sqlite3.OperationalError("disk I/O error")):
            status, reply, _ = self.post(body())
        self.assertEqual((503, {"error": "unavailable"}), (status, reply))
        self.assertEqual([], self.rows())

    def test_storage_budget_rejects_without_dropping_reports(self):
        self.store.budget = 30
        self.assertEqual(201, self.post(body(description="a" * 20))[0])
        self.assertEqual((503, {"error": "storage_full"}), self.post(body(description="b" * 20))[:2])
        self.assertEqual(1, len(self.rows()))

    def test_rate_limit_per_client(self):
        self.stop()
        self.start(self.store, server.RateLimiter(limit=2))
        self.assertEqual([201, 201], [self.post(body())[0] for _ in range(2)])
        status, reply, headers = self.post(body())
        self.assertEqual((429, {"error": "rate_limited"}), (status, reply))
        self.assertGreater(int(headers["Retry-After"]), 0)
        # A different client behind the trusted proxy has its own window.
        self.assertEqual(201, self.post(body(), headers={"X-Forwarded-For": "203.0.113.9"})[0])

    def test_purge_removes_only_expired_reports_and_show_reads_one(self):
        old = self.post(body(description="旧"))[1]["reportId"]
        self.now[0] += server.RETENTION_SECONDS + 1
        new = self.post(body(description="新", diagnostics=DIAGNOSTICS))[1]["reportId"]
        self.assertEqual(1, self.store.purge())
        self.assertIsNone(self.store.show(old))
        report = self.store.show(new)
        self.assertEqual(("新", DIAGNOSTICS), (report["description"], report["diagnostics"]))

    def test_other_paths_and_methods(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        conn.request("GET", server.PATH)
        self.assertEqual(405, conn.getresponse().status)
        conn.close()
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        conn.request("GET", "/feedback.db")
        self.assertEqual(404, conn.getresponse().status)
        conn.close()


if __name__ == "__main__":
    unittest.main()
