package com.feiyu.notes.support

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface FeedbackResult {
    /** The service confirmed it stored the report (HTTP 200/201 with a valid id). */
    data class Received(val reportId: String) : FeedbackResult
    data class Failed(val reason: FeedbackFailure, val retryAfterSeconds: Long? = null) : FeedbackResult
}

enum class FeedbackFailure {
    /** Never reached the service: safe to say it was not sent. */
    OFFLINE,
    /** Timed out or dropped after sending: the service may or may not have it. */
    UNCONFIRMED,
    REJECTED, CONFLICT, TOO_LARGE, RATE_LIMITED, UNAVAILABLE,
    /** Any other response, including a malformed success. */
    BAD_RESPONSE,
}

/** `POST /api/v1/feedback`, schemaVersion 1 (plan 0.3.2 反馈接口). One attempt, no redirects. */
class FeedbackClient(
    private val url: String = SupportServer.FEEDBACK,
    private val isAllowed: (HttpUrl) -> Boolean = SupportServer::isTrusted,
    private val http: OkHttpClient = defaultHttp,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun submit(body: String): FeedbackResult = runInterruptible(io) {
        val target = url.toHttpUrlOrNull()?.takeIf(isAllowed) ?: return@runInterruptible FeedbackResult.Failed(FeedbackFailure.BAD_RESPONSE)
        val request = Request.Builder().url(target).post(body.toRequestBody(JSON)).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.source().let { source ->
                    if (source.request(MAX_RESPONSE_BYTES + 1)) null else source.buffer.readUtf8()
                }
                when (response.code) {
                    200, 201 -> reportId(text)?.let { FeedbackResult.Received(it) } ?: FeedbackResult.Failed(FeedbackFailure.BAD_RESPONSE)
                    400, 415 -> FeedbackResult.Failed(FeedbackFailure.REJECTED)
                    409 -> FeedbackResult.Failed(FeedbackFailure.CONFLICT)
                    413 -> FeedbackResult.Failed(FeedbackFailure.TOO_LARGE)
                    429 -> FeedbackResult.Failed(FeedbackFailure.RATE_LIMITED, response.header("Retry-After")?.toLongOrNull())
                    503 -> FeedbackResult.Failed(FeedbackFailure.UNAVAILABLE)
                    else -> FeedbackResult.Failed(FeedbackFailure.BAD_RESPONSE)
                }
            }
        } catch (e: UnknownHostException) {
            FeedbackResult.Failed(FeedbackFailure.OFFLINE)
        } catch (e: ConnectException) {
            FeedbackResult.Failed(FeedbackFailure.OFFLINE)
        } catch (e: IOException) {
            FeedbackResult.Failed(FeedbackFailure.UNCONFIRMED)
        }
    }

    private fun reportId(text: String?): String? = runCatching {
        val id = ((Json.parseToJsonElement(text!!) as JsonObject)["reportId"] as JsonPrimitive).content
        id.takeIf { UUID.fromString(it).toString() == it.lowercase() }
    }.getOrNull()

    companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024L
        const val MAX_DESCRIPTION = 4000
        const val TIMEOUT_SECONDS = 20L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val defaultHttp by lazy {
            OkHttpClient.Builder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

        /** Trimmed description is valid at 1..[MAX_DESCRIPTION] Unicode code points. */
        fun isValidDescription(text: String): Boolean = text.trim().let { it.codePointCount(0, it.length) in 1..MAX_DESCRIPTION }

        /** The request body; diagnostics are null unless the user chose to attach them. */
        fun encode(submissionId: String, description: String, diagnostics: DiagnosticSnapshot?): String = buildJsonObject {
            put("schemaVersion", 1)
            put("submissionId", submissionId)
            put("description", description.trim())
            put("diagnostics", diagnostics?.let { Diagnostics.json.encodeToJsonElement(DiagnosticSnapshot.serializer(), it) } ?: JsonNull)
        }.toString()
    }
}
