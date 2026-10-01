package com.feiyu.notes.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** P4: request body, receipt handling and every failure the service defines. Synthetic data only. */
class FeedbackClientTest {
    private lateinit var server: MockWebServer
    private val id = UUID.randomUUID().toString()
    private val reportId = UUID.randomUUID().toString()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun client(http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()) =
        FeedbackClient(server.url("/api/v1/feedback").toString(), isAllowed = { it.host == server.hostName }, http = http, io = Dispatchers.Unconfined)

    private fun respond(code: Int, body: String = "", header: Pair<String, String>? = null) = server.enqueue(
        MockResponse.Builder().code(code).body(body).apply { header?.let { addHeader(it.first, it.second) } }.build()
    )

    private fun submit(body: String = FeedbackClient.encode(id, "问题", null), c: FeedbackClient = client()) = runBlocking { c.submit(body) }

    @Test fun bodyFollowsSchemaAndOmitsDiagnosticsUnlessChosen() {
        val plain = Json.parseToJsonElement(FeedbackClient.encode(id, "  问题描述  ", null)) as JsonObject
        assertEquals(setOf("schemaVersion", "submissionId", "description", "diagnostics"), plain.keys)
        assertEquals("1", plain["schemaVersion"].toString())
        assertEquals("问题描述", plain["description"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, plain["diagnostics"])

        val snapshot = DiagnosticSnapshot(app = AppInfo("0.3.2", 7), device = DeviceInfo("Synthetic", "Test", 36), events = emptyList(), crash = null, truncated = false)
        val withDiagnostics = Json.parseToJsonElement(FeedbackClient.encode(id, "x", snapshot)) as JsonObject
        assertEquals(setOf("schemaVersion", "app", "device", "events", "crash", "truncated"), (withDiagnostics["diagnostics"] as JsonObject).keys)
    }

    @Test fun descriptionLimitCountsCodePoints() {
        assertTrue(FeedbackClient.isValidDescription("🐟".repeat(FeedbackClient.MAX_DESCRIPTION)))
        assertFalse(FeedbackClient.isValidDescription("🐟".repeat(FeedbackClient.MAX_DESCRIPTION + 1)))
        assertFalse(FeedbackClient.isValidDescription("   "))
    }

    @Test fun receiptOnlyFromValid200Or201() {
        respond(201, """{"reportId":"$reportId"}""")
        assertEquals(FeedbackResult.Received(reportId), submit())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertNull(request.headers["Authorization"])
        assertEquals(id, (Json.parseToJsonElement(request.body!!.utf8()) as JsonObject)["submissionId"]!!.jsonPrimitive.content)

        respond(200, """{"reportId":"$reportId"}""")
        assertEquals(FeedbackResult.Received(reportId), submit())
        for (bad in listOf("", "{}", """{"reportId":"not-a-uuid"}""", "[1]")) {
            respond(201, bad)
            assertEquals(bad, FeedbackResult.Failed(FeedbackFailure.BAD_RESPONSE), submit())
        }
    }

    @Test fun serviceErrorsMapToFixedFailures() {
        val cases = listOf(
            400 to FeedbackFailure.REJECTED, 415 to FeedbackFailure.REJECTED, 409 to FeedbackFailure.CONFLICT,
            413 to FeedbackFailure.TOO_LARGE, 503 to FeedbackFailure.UNAVAILABLE, 500 to FeedbackFailure.BAD_RESPONSE,
            302 to FeedbackFailure.BAD_RESPONSE,
        )
        for ((code, failure) in cases) {
            respond(code, """{"error":"x"}""")
            assertEquals("HTTP $code", FeedbackResult.Failed(failure), submit())
        }
        respond(429, """{"error":"rate_limited"}""", "Retry-After" to "42")
        assertEquals(FeedbackResult.Failed(FeedbackFailure.RATE_LIMITED, 42), submit())
    }

    @Test fun oversizedResponseIsNotTrusted() {
        respond(201, """{"reportId":"$reportId","pad":"${"x".repeat(FeedbackClient.MAX_RESPONSE_BYTES.toInt())}"}""")
        assertEquals(FeedbackResult.Failed(FeedbackFailure.BAD_RESPONSE), submit())
    }

    @Test fun timeoutAfterSendingIsUnconfirmedAndSentOnce() {
        server.enqueue(MockResponse.Builder().body("""{"reportId":"$reportId"}""").headersDelay(2, TimeUnit.SECONDS).build())
        val http = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        assertEquals(FeedbackResult.Failed(FeedbackFailure.UNCONFIRMED), submit(c = client(http)))
        assertEquals(1, server.requestCount)
    }

    @Test fun droppedConnectionIsUnconfirmed() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        assertEquals(FeedbackResult.Failed(FeedbackFailure.UNCONFIRMED), submit())
    }

    @Test fun unreachableServerIsOffline() {
        val url = server.url("/api/v1/feedback").toString()
        server.close()
        val result = runBlocking { FeedbackClient(url, isAllowed = { true }, io = Dispatchers.Unconfined).submit("{}") }
        assertEquals(FeedbackResult.Failed(FeedbackFailure.OFFLINE), result)
    }

    @Test fun untrustedEndpointIsNeverCalled() {
        val result = runBlocking { FeedbackClient(server.url("/x").toString(), io = Dispatchers.Unconfined).submit("{}") }
        assertEquals(FeedbackResult.Failed(FeedbackFailure.BAD_RESPONSE), result)
        assertEquals(0, server.requestCount)
    }

    @Test fun draftSurvivesReloadAndBrokenFiles() {
        val dir = Files.createTempDirectory("draft").toFile()
        try {
            val store = FeedbackDraftStore(dir.resolve("support/feedback-draft.json"))
            assertEquals(FeedbackDraft(), store.load())
            val draft = FeedbackDraft("草稿", includeDiagnostics = true, pending = PendingSubmission(id, "{}", 1))
            assertTrue(store.save(draft))
            assertEquals(draft, FeedbackDraftStore(dir.resolve("support/feedback-draft.json")).load())
            dir.resolve("support/feedback-draft.json").writeText("{broken")
            assertEquals(FeedbackDraft(), store.load())
            store.clear()
            assertEquals(FeedbackDraft(), store.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
