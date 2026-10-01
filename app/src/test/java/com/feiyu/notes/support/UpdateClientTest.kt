package com.feiyu.notes.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** P3: version comparison, strict manifest parsing and URL policy against a local mock server. */
class UpdateClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    /** Tests run over plain HTTP on localhost; production allows only [SupportServer.isTrusted]. */
    private fun client(timeoutMillis: Long = 5_000) = UpdateClient(
        manifestUrl = server.url("/updates/android.json").toString(),
        isAllowed = { it.host == server.hostName && it.port == server.port },
        io = Dispatchers.Unconfined,
        timeoutMillis = timeoutMillis,
    )

    private fun page() = server.url("/feiyu/").toString()

    private fun manifest(
        versionCode: Any = 7,
        minSdk: Any = 26,
        notes: String = """{"zh":"中文说明","en":"English notes"}""",
        page: String = page(),
        schema: Any = 1,
    ) = """{"schemaVersion":$schema,"versionCode":$versionCode,"versionName":"0.3.2","minSdk":$minSdk,"notes":$notes,"downloadPageUrl":"$page"}"""

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun check(installed: Long = 6, sdk: Int = 36, language: String = "zh", timeoutMillis: Long = 5_000) =
        runBlocking { client(timeoutMillis).check(installed, sdk, language) }

    @Test fun newerVersionIsOfferedWithLocalizedNotes() {
        respond(manifest())
        assertEquals(UpdateResult.Available("0.3.2", "中文说明", page()), check())
        val request = server.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
    }

    @Test fun missingLanguageFallsBackToEnglish() {
        respond(manifest(notes = """{"en":"English notes"}"""))
        assertEquals("English notes", (check(language = "zh") as UpdateResult.Available).notes)
    }

    @Test fun sameOrOlderVersionIsUpToDate() {
        respond(manifest(versionCode = 6))
        assertEquals(UpdateResult.UpToDate, check(installed = 6))
        respond(manifest(versionCode = 5))
        assertEquals(UpdateResult.UpToDate, check(installed = 6))
    }

    @Test fun newerVersionNeedingNewerAndroidIsUnsupported() {
        respond(manifest(minSdk = 30))
        assertEquals(UpdateResult.Unsupported("0.3.2", 30), check(sdk = 29))
    }

    @Test fun invalidManifestsFail() {
        val invalid = listOf(
            "not json",
            "[]",
            manifest(schema = 2),
            manifest(versionCode = "\"7\""),
            manifest(versionCode = 7.5),
            manifest(minSdk = "true"),
            manifest(notes = """{"zh":"只有中文"}"""),
            manifest(notes = """{"en":1}"""),
            """{"schemaVersion":1,"versionCode":7,"versionName":"0.3.2","minSdk":26,"notes":{"en":"x"}}""",
        )
        for (body in invalid) {
            respond(body)
            assertEquals(body, UpdateResult.Failed(UpdateFailure.INVALID), check())
        }
    }

    @Test fun oversizedResponseFails() {
        respond(manifest().dropLast(1) + ",\"pad\":\"" + "x".repeat(UpdateClient.MAX_BYTES.toInt()) + "\"}")
        assertEquals(UpdateResult.Failed(UpdateFailure.INVALID), check())
    }

    @Test fun httpErrorIsReportedNotUpToDate() {
        respond("", code = 503)
        assertEquals(UpdateResult.Failed(UpdateFailure.HTTP, 503), check())
    }

    @Test fun timeoutFails() {
        server.enqueue(MockResponse.Builder().body(manifest()).headersDelay(2, TimeUnit.SECONDS).build())
        assertEquals(UpdateResult.Failed(UpdateFailure.TIMEOUT), check(timeoutMillis = 300))
    }

    @Test fun networkFailureFails() {
        val url = server.url("/updates/android.json").toString()
        server.close()
        val result = runBlocking {
            UpdateClient(url, isAllowed = { true }, io = Dispatchers.Unconfined).check(6, 36, "en")
        }
        assertEquals(UpdateResult.Failed(UpdateFailure.NETWORK), result)
    }

    @Test fun untrustedDownloadPageFails() {
        respond(manifest(page = "https://github.com/Yongzhaooo/FeiyuNote/releases"))
        assertEquals(UpdateResult.Failed(UpdateFailure.INVALID), check())
    }

    @Test fun redirectsAreFollowedOnlyWithinPolicy() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/updates/v2.json").build())
        respond(manifest())
        assertTrue(check() is UpdateResult.Available)
        assertEquals("/updates/android.json", server.takeRequest().url.encodedPath)
        assertEquals("/updates/v2.json", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://github.com/x.json").build())
        assertEquals(UpdateResult.Failed(UpdateFailure.INVALID), check())
    }

    @Test fun redirectLoopFails() {
        repeat(UpdateClient.MAX_REDIRECTS + 1) {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/updates/android.json").build())
        }
        assertEquals(UpdateResult.Failed(UpdateFailure.INVALID), check())
    }

    @Test fun productionPolicyAcceptsOnlyTheSupportHostOverHttps() {
        assertTrue(SupportServer.isTrusted(SupportServer.UPDATE_MANIFEST.toHttpUrl()))
        assertTrue(SupportServer.isTrusted(SupportServer.DOWNLOAD_PAGE.toHttpUrl()))
        listOf(
            "http://feiyunote.cangming.fyi/updates/android.json",
            "https://feiyunote.cangming.fyi:8443/feiyu/",
            "https://user:pw@feiyunote.cangming.fyi/feiyu/",
            "https://feiyunote.cangming.fyi.evil.example/feiyu/",
            "https://vps.cangming.fyi/feiyu/",
            "https://github.com/Yongzhaooo/FeiyuNote/releases",
        ).forEach { assertFalse(it, SupportServer.isTrusted(it.toHttpUrl())) }
    }
}
