package com.feiyu.notes.support

import kotlinx.serialization.Serializable
import java.io.File

/** A submission frozen at preview time; retries resend exactly this body with the same id. */
@Serializable
data class PendingSubmission(val submissionId: String, val body: String, val createdAtMillis: Long)

@Serializable
data class FeedbackDraft(
    val description: String = "",
    val includeDiagnostics: Boolean = false,
    val pending: PendingSubmission? = null,
)

/**
 * Keeps the feedback draft across rotation, navigation and process death (spec §9 F02) in one
 * private JSON file. Writes go to a temp file first, so a crash mid-write keeps the old draft.
 */
class FeedbackDraftStore(private val file: File) {
    fun load(): FeedbackDraft =
        runCatching { Diagnostics.json.decodeFromString<FeedbackDraft>(file.readText()) }.getOrDefault(FeedbackDraft())

    fun save(draft: FeedbackDraft): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(Diagnostics.json.encodeToString(FeedbackDraft.serializer(), draft))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file))
        }
    }.isSuccess

    fun clear() {
        file.delete()
    }

    companion object {
        /** The service keeps its duplicate check for 30 days; an older pending id can no longer be confirmed. */
        const val PENDING_TTL_MILLIS = 30L * 24 * 3600 * 1000
    }
}
