package com.feiyu.notes.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * A Skill is read as text only: the SKILL.md instructions become a template instruction.
 * Scripts and any other files in the Skill are never fetched or run.
 */
object SkillImport {
    const val MAX_BYTES = 64 * 1024L

    data class Skill(val name: String, val description: String, val instruction: String, val url: String)

    enum class Failure { INVALID_URL, NOT_FOUND, TOO_LARGE, EMPTY, NETWORK }
    class SkillException(val failure: Failure) : Exception(failure.name)

    private val http by lazy {
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }

    /**
     * Maps a GitHub repository, folder (`tree`), file (`blob`) or raw link to the raw SKILL.md URL.
     * Only https on github.com / raw.githubusercontent.com is accepted; anything else returns null.
     */
    fun rawUrl(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawQuery != null) return null
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val path = when (uri.host) {
            "raw.githubusercontent.com" -> parts.takeIf { it.size >= 4 }
            "github.com" -> when {
                parts.size == 2 -> parts + "HEAD"
                parts.size >= 4 && parts[2] in setOf("blob", "tree") -> parts.take(2) + parts.drop(3)
                else -> null
            }
            else -> null
        } ?: return null
        val file = if (path.last().endsWith(".md", ignoreCase = true)) path else path + "SKILL.md"
        return "https://raw.githubusercontent.com/" + file.joinToString("/")
    }

    /** Splits optional YAML front matter (`name`, `description`) from the instruction body. */
    fun parse(text: String, url: String): Skill {
        val normalized = text.removePrefix("﻿").replace("\r\n", "\n")
        var meta = emptyMap<String, String>()
        var body = normalized
        if (normalized.startsWith("---\n")) {
            val end = normalized.indexOf("\n---", 4)
            if (end > 0) {
                meta = normalized.substring(4, end).lineSequence()
                    .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
                    .associate { (k, v) -> k.trim() to v.trim().removeSurrounding("\"").removeSurrounding("'") }
                body = normalized.substring(end + 4).substringAfter('\n', "")
            }
        }
        val instruction = body.trim()
        if (instruction.isEmpty()) throw SkillException(Failure.EMPTY)
        val folder = url.split('/').dropLast(1).lastOrNull().orEmpty()
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: folder.ifBlank { "Skill" }
        return Skill(name.take(60), meta["description"].orEmpty(), instruction, url)
    }

    suspend fun fetch(input: String): Skill {
        val url = rawUrl(input) ?: throw SkillException(Failure.INVALID_URL)
        val text = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (response.code == 404) throw SkillException(Failure.NOT_FOUND)
                    if (!response.isSuccessful) throw SkillException(Failure.NETWORK)
                    val source = response.body.source()
                    if (source.request(MAX_BYTES + 1)) throw SkillException(Failure.TOO_LARGE)
                    source.buffer.readUtf8()
                }
            } catch (e: java.io.IOException) {
                throw SkillException(Failure.NETWORK)
            }
        }
        return parse(text, url)
    }
}
