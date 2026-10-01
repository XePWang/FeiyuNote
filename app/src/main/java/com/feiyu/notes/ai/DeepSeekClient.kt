package com.feiyu.notes.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Non-streaming DeepSeek chat/completions client (spec §6). Configuration is injected;
 * the client never reads settings. Cancelling the calling coroutine cancels the HTTP call.
 */
class DeepSeekClient(
    private val config: AiConfig,
    private val http: OkHttpClient = defaultHttp,
    private val encodeImage: (File) -> ByteArray = ::scaledJpeg,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun generate(input: AiInput): AiReply {
        if (config.apiKey.isBlank()) throw AiError.MissingKey()
        val body = withContext(io) { requestJson(input) }.toString()
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody(JSON))
            .build()
        val (code, text) = http.newCall(request).await()
        return parse(code, text)
    }

    /** Authenticated, non-generating connectivity check; unknown metadata is ignored. */
    suspend fun listModels(): List<AiModel> = withTimeout(30_000) {
        if (config.apiKey.isBlank()) throw AiError.MissingKey()
        val url = config.endpoint.toHttpUrl().newBuilder().encodedPath("/models").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${config.apiKey}").get().build()
        val (code, text) = http.newCall(request).await()
        checkStatus(code, text)
        runCatching {
            Json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray.map { item ->
                val model = item.jsonObject
                val id = model["id"]!!.jsonPrimitive.content
                require(id.isNotBlank())
                val effort = model["effort"]?.jsonObject
                AiModel(id, model["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    effort?.get("supported_levels")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    effort?.get("default_level")?.jsonPrimitive?.contentOrNull,
                    model["input_modalities"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
            }.distinctBy { it.id }.also { require(it.isNotEmpty()) }
        }.getOrElse { throw AiError.BadResponse("Invalid model list") }
    }

    internal fun requestJson(input: AiInput): JsonObject = buildJsonObject {
        put("model", config.model)
        put("stream", false)
        put("reasoning_effort", config.effort)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", input.systemText)
            }
            for (m in input.messages) addJsonObject {
                put("role", m.role.name.lowercase())
                if (m.images.isEmpty()) {
                    put("content", m.text)
                } else {
                    putJsonArray("content") {
                        if (m.text.isNotBlank()) addJsonObject {
                            put("type", "text")
                            put("text", m.text)
                        }
                        for (file in m.images) addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(readImage(file)))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readImage(file: File): ByteArray =
        runCatching { encodeImage(file) }.getOrElse { throw AiError.ImageUnreadable(file.name) }

    private fun checkStatus(code: Int, text: String) {
        val detail = errorMessage(text)
        when {
            code == 401 || code == 403 -> throw AiError.Auth(code)
            code == 402 || code == 429 -> throw AiError.Quota(code)
            code == 413 -> throw AiError.TooLarge(code, detail)
            code == 400 && detail.contains("length", ignoreCase = true) -> throw AiError.TooLarge(code, detail)
            code !in 200..299 -> throw AiError.Server(code, detail)
        }
    }

    private fun parse(code: Int, text: String): AiReply {
        checkStatus(code, text)
        val content = runCatching {
            val choice = Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray.first().jsonObject
            choice["message"]!!.jsonObject["content"]?.jsonPrimitive?.contentOrNull
        }.getOrElse { throw AiError.BadResponse(it.message ?: "unexpected JSON") }
        if (content.isNullOrBlank()) throw AiError.EmptyAnswer()
        return AiReply(content.trim())
    }

    private fun errorMessage(text: String): String = runCatching {
        Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }.getOrDefault(text.take(200))

    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(AiError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { it.code to it.body.string() } }
                result.onSuccess { cont.resume(it) }
                    .onFailure { if (!cont.isCancelled) cont.resumeWithException(AiError.Network(it)) }
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        /** Downscales so the long edge is at most [AiDefaults.IMAGE_MAX_EDGE], honouring EXIF rotation. */
        fun scaledJpeg(file: File): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AiDefaults.IMAGE_MAX_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("decode failed")
            val longEdge = max(decoded.width, decoded.height)
            val scale = if (longEdge > AiDefaults.IMAGE_MAX_EDGE) AiDefaults.IMAGE_MAX_EDGE.toFloat() / longEdge else 1f
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(exifRotation(file).toFloat())
            }
            val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            return ByteArrayOutputStream().use { stream ->
                out.compress(Bitmap.CompressFormat.JPEG, AiDefaults.JPEG_QUALITY, stream)
                stream.toByteArray()
            }
        }

        private fun exifRotation(file: File): Int = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }
}
