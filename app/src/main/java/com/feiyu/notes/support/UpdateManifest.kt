package com.feiyu.notes.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** `GET /updates/android.json`, schemaVersion 1 (plan 0.3.2 更新接口). */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    /** Language code → plain-text release notes; always contains "en". */
    val notes: Map<String, String>,
    val downloadPageUrl: String,
) {
    fun notesFor(language: String): String = notes[language] ?: notes.getValue("en")

    companion object {
        const val SCHEMA_VERSION = 1L

        /**
         * Strict parse: a missing field, a wrong type (including quoted numbers), an unknown schema
         * or notes without English all return null. Unknown extra fields are ignored.
         */
        fun parse(text: String): UpdateManifest? = runCatching {
            val json = Json.parseToJsonElement(text) as? JsonObject ?: return null
            fun string(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String) = (json[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (number("schemaVersion") != SCHEMA_VERSION) return null
            val notes = (json["notes"] as? JsonObject)?.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            } ?: return null
            if ("en" !in notes) return null
            UpdateManifest(
                versionCode = number("versionCode")?.takeIf { it > 0 } ?: return null,
                versionName = string("versionName")?.takeIf { it.isNotBlank() } ?: return null,
                minSdk = number("minSdk")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null,
                notes = notes,
                downloadPageUrl = string("downloadPageUrl") ?: return null,
            )
        }.getOrNull()
    }
}
