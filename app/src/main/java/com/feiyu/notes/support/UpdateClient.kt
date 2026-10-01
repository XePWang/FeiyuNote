package com.feiyu.notes.support

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

sealed interface UpdateResult {
    data class Available(val versionName: String, val notes: String, val downloadPageUrl: String) : UpdateResult
    /** A newer version exists but needs a newer Android; download stays disabled. */
    data class Unsupported(val versionName: String, val minSdk: Int) : UpdateResult
    data object UpToDate : UpdateResult
    data class Failed(val reason: UpdateFailure, val httpStatus: Int? = null) : UpdateResult
}

enum class UpdateFailure { NETWORK, TIMEOUT, HTTP, INVALID }

/**
 * Manual update check (spec §9 U01). A plain client of its own: no credentials, cookies, retries or
 * automatic redirects; each redirect hop is re-checked against [isAllowed]. Never throws on failure.
 */
class UpdateClient(
    private val manifestUrl: String = SupportServer.UPDATE_MANIFEST,
    private val isAllowed: (HttpUrl) -> Boolean = SupportServer::isTrusted,
    private val http: OkHttpClient = defaultHttp,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    suspend fun check(installedVersionCode: Long, sdk: Int, language: String): UpdateResult = runInterruptible(io) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var url = manifestUrl.toHttpUrlOrNull()?.takeIf(isAllowed) ?: return@runInterruptible UpdateResult.Failed(UpdateFailure.INVALID)
        try {
            repeat(MAX_REDIRECTS + 1) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return@runInterruptible UpdateResult.Failed(UpdateFailure.TIMEOUT)
                val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
                call.execute().use { response ->
                    if (response.isRedirect) {
                        url = response.header("Location")?.let(url::resolve)?.takeIf(isAllowed)
                            ?: return@runInterruptible UpdateResult.Failed(UpdateFailure.INVALID)
                        return@use
                    }
                    if (!response.isSuccessful) return@runInterruptible UpdateResult.Failed(UpdateFailure.HTTP, response.code)
                    val source = response.body.source()
                    if (source.request(MAX_BYTES + 1)) return@runInterruptible UpdateResult.Failed(UpdateFailure.INVALID)
                    val manifest = UpdateManifest.parse(source.buffer.readUtf8())
                    return@runInterruptible evaluate(manifest, installedVersionCode, sdk, language)
                }
            }
            UpdateResult.Failed(UpdateFailure.INVALID) // too many redirects
        } catch (e: InterruptedIOException) {
            UpdateResult.Failed(UpdateFailure.TIMEOUT)
        } catch (e: IOException) {
            UpdateResult.Failed(UpdateFailure.NETWORK)
        }
    }

    private fun evaluate(manifest: UpdateManifest?, installed: Long, sdk: Int, language: String): UpdateResult {
        if (manifest == null) return UpdateResult.Failed(UpdateFailure.INVALID)
        val page = manifest.downloadPageUrl.toHttpUrlOrNull()?.takeIf(isAllowed) ?: return UpdateResult.Failed(UpdateFailure.INVALID)
        return when {
            manifest.versionCode <= installed -> UpdateResult.UpToDate
            sdk < manifest.minSdk -> UpdateResult.Unsupported(manifest.versionName, manifest.minSdk)
            else -> UpdateResult.Available(manifest.versionName, manifest.notesFor(language), page.toString())
        }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024L
        const val TIMEOUT_MILLIS = 15_000L
        const val MAX_REDIRECTS = 3

        private val defaultHttp by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

        fun installedVersionCode(context: Context): Long =
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }
}
