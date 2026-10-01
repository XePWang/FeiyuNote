package com.feiyu.notes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** P3: request shape and error mapping against a local mock server; synthetic data only. */
class DeepSeekClientTest {
    @Test fun configurationStringDoesNotExposeKey() {
        val rendered = AiConfig(apiKey = "synthetic-secret").toString()
        assertTrue(!rendered.contains("synthetic-secret") && rendered.contains("<redacted>"))
    }
    private lateinit var server: MockWebServer
    private val image = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close(); image.delete() }

    private fun client(key: String = "test-key", model: String = "deepseek-flash") = DeepSeekClient(
        config = AiConfig(apiKey = key, model = model, endpoint = server.url("/chat/completions").toString()),
        encodeImage = { "JPEG:${it.name}".toByteArray() },
        io = Dispatchers.Unconfined,
    )

    private fun ok(content: String?) = MockResponse.Builder().code(200).body(
        """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":${content?.let { Json.encodeToString(it) } ?: "null"}},"finish_reason":"stop"}],"usage":{"total_tokens":3}}"""
    ).build()

    private val input = AiInput(
        systemText = "sys",
        messages = listOf(
            AiMessage(AiRole.USER, "第一问"),
            AiMessage(AiRole.ASSISTANT, "第一答"),
            AiMessage(AiRole.USER, "看图", listOf(image)),
        ),
    )

    @Test fun sendsRolesImagesModelAndAuth() = runBlocking {
        server.enqueue(ok("  讲解正文  "))
        val reply = client(model = "custom-model").generate(input)
        assertEquals("讲解正文", reply.text)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.headers["Authorization"])
        val json = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("custom-model", json["model"]!!.jsonPrimitive.content)
        assertEquals("false", json["stream"]!!.jsonPrimitive.content)
        val messages = json["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("第一答", messages[2]["content"]!!.jsonPrimitive.content) // assistant: plain text, no image
        val parts = messages[3]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("看图", parts[0]["text"]!!.jsonPrimitive.content)
        val url = parts[1]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString("JPEG:${image.name}".toByteArray()), url)
    }

    @Test fun assistantMessagesCannotCarryImages() {
        assertTrue(runCatching { AiMessage(AiRole.ASSISTANT, "x", listOf(image)) }.isFailure)
    }

    @Test fun errorsAreDistinguishable() = runBlocking {
        suspend fun expect(response: MockResponse, type: Class<out AiError>) {
            val before = server.requestCount
            server.enqueue(response)
            try {
                client().generate(input); fail("expected $type")
            } catch (e: AiError) {
                assertTrue("got ${e.javaClass}", type.isInstance(e))
            }
            assertEquals("failed requests are not silently resent", before + 1, server.requestCount)
        }
        expect(ok(""), AiError.EmptyAnswer::class.java)
        expect(ok(null), AiError.EmptyAnswer::class.java)
        expect(MockResponse.Builder().code(401).body("""{"error":{"message":"bad key"}}""").build(), AiError.Auth::class.java)
        expect(MockResponse.Builder().code(429).body("{}").build(), AiError.Quota::class.java)
        expect(MockResponse.Builder().code(402).body("{}").build(), AiError.Quota::class.java)
        expect(MockResponse.Builder().code(413).body("{}").build(), AiError.TooLarge::class.java)
        expect(MockResponse.Builder().code(500).body("oops").build(), AiError.Server::class.java)
        expect(MockResponse.Builder().code(200).body("not json").build(), AiError.BadResponse::class.java)
        expect(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build(), AiError.Network::class.java)
    }

    @Test fun missingKeyFailsWithoutRequest() = runBlocking {
        try {
            client(key = "").generate(input); fail()
        } catch (e: AiError.MissingKey) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(ok("late").newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val call = async(Dispatchers.IO) { client().generate(input) }
        delay(300)
        call.cancel()
        try {
            withTimeout(3_000) { call.await() }
            fail("should be cancelled")
        } catch (e: CancellationException) {
        }
        assertTrue("returned promptly", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }
}
