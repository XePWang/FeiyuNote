package com.feiyu.notes.support

import com.feiyu.notes.ai.AiError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

/** P1: allow-listed content, retention and size limits, crash capture. Synthetic data only. */
class DiagnosticsTest {
    private val dir = Files.createTempDirectory("diagnostics").toFile()
    private var now = Instant.parse("2026-10-01T08:00:00Z")
    private val app = AppInfo("0.3.2", 7)
    private val device = DeviceInfo("Synthetic", "Test", 36)

    private fun diagnostics(root: File = dir, maxLog: Long = Diagnostics.MAX_LOG_BYTES, maxSnapshot: Int = Diagnostics.MAX_SNAPSHOT_BYTES) =
        Diagnostics(root, app, device, clock = { now }, maxLogBytes = maxLog, maxSnapshotBytes = maxSnapshot)

    @After fun tearDown() { dir.deleteRecursively() }

    private val secrets = listOf("sk-synthetic", "api.deepseek.com", "/data/user", "私密正文", "photo-123.jpg")

    @Test fun failuresKeepOnlyKindStatusAndFrames() = runBlocking {
        val d = diagnostics()
        d.recordFailure(DiagnosticOperation.GENERATE, AiError.Server(500, "sk-synthetic 私密正文 https://api.deepseek.com/x"), 1200)
        d.recordFailure(DiagnosticOperation.CONNECTION_TEST, AiError.Network(SocketTimeoutException("api.deepseek.com/data/user")))
        d.recordFailure(DiagnosticOperation.NOTE_EXPORT, IOException("/data/user/0/com.feiyu.notes/files/photo-123.jpg"))
        d.recordFailure(DiagnosticOperation.GENERATE, AiError.ImageUnreadable("photo-123.jpg"))
        d.record(DiagnosticOperation.APP_START, DiagnosticResult.OK)
        assertTrue(d.recordCrash(IllegalStateException("sk-synthetic 私密正文", IOException("/data/user/photo-123.jpg"))))

        val snapshot = d.snapshot()
        val text = d.encode(snapshot)
        secrets.forEach { assertFalse("leaked $it", text.contains(it)) }
        assertEquals(
            listOf(ErrorKind.SERVER to 500, ErrorKind.TIMEOUT to null, ErrorKind.IO to null, ErrorKind.IMAGE_UNREADABLE to null, null to null),
            snapshot.events.map { it.error to it.httpStatus },
        )
        assertEquals("2026-10-01T08:00:00Z", snapshot.events.first().time)
        val crash = snapshot.crash!!
        assertEquals(listOf(IllegalStateException::class.java.name, IOException::class.java.name), crash.chain.map { it.type })
        assertTrue(crash.chain.first().frames.any { it.className == DiagnosticsTest::class.java.name && it.file == "DiagnosticsTest.kt" })
    }

    @Test fun snapshotMatchesTheFeedbackSchema() = runBlocking {
        val d = diagnostics()
        d.record(DiagnosticOperation.APP_START, DiagnosticResult.OK)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(d.encode(d.snapshot())) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("schemaVersion", "app", "device", "events", "crash", "truncated"), json.keys)
        assertEquals("1", json["schemaVersion"].toString())
        assertEquals("null", json["crash"].toString())
    }

    @Test fun oldDaysAndCrashesExpireAfterSevenDays() = runBlocking {
        val d = diagnostics()
        d.record(DiagnosticOperation.APP_START, DiagnosticResult.OK)
        d.recordCrash(RuntimeException())
        now = now.plus(6, ChronoUnit.DAYS)
        d.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK)
        assertEquals(2, d.snapshot().events.size)
        assertNotNull(d.snapshot().crash)

        now = now.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
        d.record(DiagnosticOperation.SUMMARIZE, DiagnosticResult.OK)
        val snapshot = d.snapshot()
        assertEquals(listOf(DiagnosticOperation.GENERATE, DiagnosticOperation.SUMMARIZE), snapshot.events.map { it.operation })
        assertNull(snapshot.crash)
        assertFalse(d.hasPendingCrash())
    }

    @Test fun logFilesStayWithinTheSizeLimit() = runBlocking {
        val limit = 8 * 1024L
        val d = diagnostics(maxLog = limit)
        repeat(3) { day ->
            repeat(60) { d.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK, durationMs = it.toLong()) }
            now = now.plus(1, ChronoUnit.DAYS)
        }
        repeat(200) { d.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK, durationMs = it.toLong()) }
        val snapshot = d.snapshot()
        assertTrue(dir.listFiles()!!.filter { it.name.startsWith("events-") }.sumOf { it.length() } <= limit)
        assertEquals(199L, snapshot.events.last().durationMs) // newest kept
    }

    @Test fun oversizedSnapshotDropsOldestWholeEventsAndStaysValid() = runBlocking {
        val limit = 4 * 1024
        val d = diagnostics(maxSnapshot = limit)
        repeat(100) { d.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK, durationMs = it.toLong()) }
        val snapshot = d.snapshot()
        val text = d.encode(snapshot)
        assertTrue(text.toByteArray().size <= limit)
        assertTrue(snapshot.truncated)
        assertEquals(99L, snapshot.events.last().durationMs)
        assertEquals(snapshot, Diagnostics.json.decodeFromString<DiagnosticSnapshot>(text))
    }

    @Test fun concurrentRecordsAreAllWritten() = runBlocking {
        val d = diagnostics()
        (1..8).map { t -> thread { repeat(50) { d.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK, durationMs = t * 1000L + it) } } }.forEach { it.join() }
        assertEquals(400, d.snapshot().events.map { it.durationMs }.distinct().size)
    }

    @Test fun diskFailureNeverThrows() = runBlocking {
        val blocked = File(dir, "not-a-dir").apply { writeText("x") }
        val d = diagnostics(root = blocked)
        d.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK)
        assertFalse(d.recordCrash(RuntimeException()))
        assertEquals(emptyList<DiagnosticEvent>(), d.snapshot().events)
    }

    @Test fun crashHandlerSavesThenAlwaysCallsThePreviousHandler() {
        val d = diagnostics()
        val seen = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> seen += e }
        val crash = RuntimeException("sk-synthetic")
        Diagnostics.crashHandler(previous) { d }.uncaughtException(Thread.currentThread(), crash)
        assertSame(crash, seen.single())
        assertTrue(d.hasPendingCrash())
        d.dismissCrash()
        assertFalse(d.hasPendingCrash())
        assertNotNull(runBlocking { d.snapshot().crash }) // still attachable to feedback

        val broken = diagnostics(root = File(dir, "file").apply { writeText("x") })
        Diagnostics.crashHandler(previous) { broken }.uncaughtException(Thread.currentThread(), crash)
        Diagnostics.crashHandler(previous) { error("no diagnostics") }.uncaughtException(Thread.currentThread(), crash)
        assertEquals(3, seen.size)
    }

    @Test fun crashChainAndFramesAreBounded() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException() else deep(n - 1)
        val root = runCatching { deep(200) }.exceptionOrNull()!!
        var e: Throwable = root
        repeat(6) { e = RuntimeException(e) }
        val record = CrashRecord.from(e, "t")
        assertEquals(CrashRecord.MAX_CHAIN, record.chain.size)
        assertTrue(record.chain.sumOf { it.frames.size } <= CrashRecord.MAX_FRAMES)
    }
}
