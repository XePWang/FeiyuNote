package com.feiyu.notes.support

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Bounded local diagnostics (spec §9 L01/L02) in a private directory: one JSON-lines file per UTC day
 * plus the latest crash. Nothing is uploaded; the feedback flow previews and sends [snapshot].
 * Every disk failure is swallowed: diagnostics never break the operation being recorded.
 */
class Diagnostics(
    private val dir: File,
    private val app: AppInfo,
    private val device: DeviceInfo,
    private val clock: () -> Instant = Instant::now,
    /** Serial writer; snapshots run on it too, so they see every earlier [record]. */
    private val writer: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val maxLogBytes: Long = MAX_LOG_BYTES,
    private val maxSnapshotBytes: Int = MAX_SNAPSHOT_BYTES,
) {
    private val scope = CoroutineScope(SupervisorJob() + writer)
    /** Guards the files against the crash handler, which writes from whichever thread is dying. */
    private val lock = ReentrantLock()

    fun record(
        operation: DiagnosticOperation,
        result: DiagnosticResult,
        error: ErrorKind? = null,
        httpStatus: Int? = null,
        durationMs: Long? = null,
    ) {
        val event = DiagnosticEvent(now(), operation, result, error, httpStatus, durationMs)
        scope.launch { locked { append(event) } }
    }

    fun recordFailure(operation: DiagnosticOperation, e: Throwable, durationMs: Long? = null) {
        val (kind, status) = ErrorKind.classify(e)
        record(operation, DiagnosticResult.FAILED, kind, status, durationMs)
    }

    suspend fun snapshot(): DiagnosticSnapshot = withContext(writer) {
        locked {
            prune()
            val events = eventFiles().flatMap { file ->
                runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
                    runCatching { json.decodeFromString<DiagnosticEvent>(line) }.getOrNull()
                }
            }
            fit(DiagnosticSnapshot(app = app, device = device, events = events, crash = readCrash(), truncated = false))
        } ?: DiagnosticSnapshot(app = app, device = device, events = emptyList(), crash = null, truncated = false)
    }

    fun encode(snapshot: DiagnosticSnapshot): String = json.encodeToString(snapshot)

    /** A crash from an earlier run still waits for the "上次运行遇到问题" prompt. */
    fun hasPendingCrash(): Boolean = File(dir, PENDING).isFile && File(dir, CRASH).isFile

    /** Viewing, sending or ignoring the prompt: this crash will not prompt again (it stays in snapshots until it expires). */
    fun dismissCrash() {
        File(dir, PENDING).delete()
    }

    suspend fun clear() = withContext(writer) {
        locked { dir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /** Bounded synchronous write from the crash handler; returns false if nothing was saved. */
    fun recordCrash(e: Throwable): Boolean {
        val held = runCatching { lock.tryLock(CRASH_LOCK_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        return try {
            var frames = CrashRecord.MAX_FRAMES
            var text = json.encodeToString(CrashRecord.from(e, now(), frames))
            while (text.toByteArray().size > MAX_CRASH_BYTES && frames > 0) {
                frames /= 2
                text = json.encodeToString(CrashRecord.from(e, now(), frames))
            }
            dir.mkdirs()
            val temp = File(dir, "$CRASH.tmp")
            temp.writeText(text)
            if (!temp.renameTo(File(dir, CRASH))) {
                File(dir, CRASH).delete()
                if (!temp.renameTo(File(dir, CRASH))) return false
            }
            File(dir, PENDING).writeText("")
            true
        } catch (t: Throwable) {
            false
        } finally {
            if (held) lock.unlock()
        }
    }

    private fun now(): String = clock().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun today(): LocalDate = clock().atZone(ZoneOffset.UTC).toLocalDate()

    private inline fun <T> locked(block: () -> T): T? {
        lock.lock()
        return try {
            runCatching(block).getOrNull()
        } finally {
            lock.unlock()
        }
    }

    private fun append(event: DiagnosticEvent) {
        val line = json.encodeToString(event)
        if (line.toByteArray().size > MAX_EVENT_BYTES) return
        dir.mkdirs()
        prune()
        // The event's own day, not the clock at write time: writes run later on the serial writer.
        val day = Instant.parse(event.time).atZone(ZoneOffset.UTC).toLocalDate()
        File(dir, "$EVENTS_PREFIX$day$EVENTS_SUFFIX").appendText(line + "\n")
        enforceTotal()
    }

    private fun eventFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith(EVENTS_PREFIX) && f.name.endsWith(EVENTS_SUFFIX) }.orEmpty().sortedBy { it.name }

    /** Drops day files older than [RETENTION_DAYS] and an expired crash. */
    private fun prune() {
        val oldest = today().minusDays(RETENTION_DAYS - 1)
        eventFiles().forEach { file ->
            val day = runCatching { LocalDate.parse(file.name.removePrefix(EVENTS_PREFIX).removeSuffix(EVENTS_SUFFIX)) }.getOrNull()
            if (day == null || day < oldest) file.delete()
        }
        val crash = File(dir, CRASH)
        if (crash.isFile) {
            val time = runCatching { Instant.parse(json.decodeFromString<CrashRecord>(crash.readText()).time) }.getOrNull()
            if (time == null || time < clock().minus(RETENTION_DAYS, ChronoUnit.DAYS)) {
                crash.delete()
                File(dir, PENDING).delete()
            }
        }
    }

    /** Keeps all event files within [maxLogBytes]: whole old days first, then the oldest half of today. */
    private fun enforceTotal() {
        val files = eventFiles().toMutableList()
        while (files.sumOf { it.length() } > maxLogBytes && files.size > 1) files.removeAt(0).delete()
        val last = files.lastOrNull() ?: return
        if (last.length() > maxLogBytes) {
            val kept = ArrayDeque<String>()
            var size = 0L
            for (line in last.readLines().asReversed()) {
                size += line.toByteArray().size + 1
                if (size > maxLogBytes / 2) break
                kept.addFirst(line)
            }
            last.writeText(kept.joinToString("") { it + "\n" })
        }
    }

    private fun readCrash(): CrashRecord? =
        runCatching { json.decodeFromString<CrashRecord>(File(dir, CRASH).readText()) }.getOrNull()

    /** Removes whole events, oldest first, until the encoded snapshot fits [maxSnapshotBytes]. */
    private fun fit(full: DiagnosticSnapshot): DiagnosticSnapshot {
        var dropped = 0
        val sizes = full.events.map { json.encodeToString(it).toByteArray().size + 1 }
        var total = json.encodeToString(full.copy(events = emptyList(), truncated = true)).toByteArray().size + sizes.sum()
        while (total > maxSnapshotBytes && dropped < sizes.size) total -= sizes[dropped++]
        return if (dropped == 0) full else full.copy(events = full.events.drop(dropped), truncated = true)
    }

    companion object {
        const val RETENTION_DAYS = 7L
        const val MAX_LOG_BYTES = 1024L * 1024
        const val MAX_CRASH_BYTES = 64 * 1024
        const val MAX_SNAPSHOT_BYTES = 256 * 1024
        const val MAX_EVENT_BYTES = 4 * 1024
        private const val CRASH_LOCK_WAIT_MS = 500L
        private const val EVENTS_PREFIX = "events-"
        private const val EVENTS_SUFFIX = ".jsonl"
        private const val CRASH = "crash.json"
        private const val PENDING = "crash.pending"

        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        private val handlerInstalled = AtomicBoolean(false)

        /** Installs once per process; [current] is read at crash time, so test environment swaps need no reinstall. */
        fun installCrashHandler(current: () -> Diagnostics?) {
            if (!handlerInstalled.compareAndSet(false, true)) return
            Thread.setDefaultUncaughtExceptionHandler(crashHandler(Thread.getDefaultUncaughtExceptionHandler(), current))
        }

        /** Saves what it can, then always hands over to the system's own handler. */
        internal fun crashHandler(previous: Thread.UncaughtExceptionHandler?, current: () -> Diagnostics?) =
            Thread.UncaughtExceptionHandler { thread, e ->
                try {
                    current()?.recordCrash(e)
                } catch (_: Throwable) {
                } finally {
                    previous?.uncaughtException(thread, e)
                }
            }
    }
}
