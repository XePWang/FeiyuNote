package com.feiyu.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feiyu.notes.FeiyuApp
import com.feiyu.notes.settings.AppLanguage
import com.feiyu.notes.support.DiagnosticOperation
import com.feiyu.notes.support.DiagnosticResult
import com.feiyu.notes.support.ErrorKind
import com.feiyu.notes.support.FeedbackClient
import com.feiyu.notes.support.FeedbackDraft
import com.feiyu.notes.support.FeedbackDraftStore
import com.feiyu.notes.support.FeedbackFailure
import com.feiyu.notes.support.FeedbackResult
import com.feiyu.notes.support.PendingSubmission
import com.feiyu.notes.support.UpdateClient
import com.feiyu.notes.support.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** What the user previews, submits and shares: always rendered from one request body. */
data class FeedbackPreview(val submissionId: String, val body: String, val description: String, val diagnostics: String?) {
    /** Plain text for share and copy: the description, then the diagnostics if attached. */
    fun shareText(diagnosticsLabel: String): String =
        if (diagnostics == null) description else "$description\n\n$diagnosticsLabel\n$diagnostics"

    companion object {
        private val pretty = Json { prettyPrint = true }

        fun from(submissionId: String, body: String): FeedbackPreview {
            val json = Json.parseToJsonElement(body) as JsonObject
            val diagnostics = json["diagnostics"]?.takeIf { it != JsonNull }
            return FeedbackPreview(
                submissionId, body, (json["description"] as JsonPrimitive).content,
                diagnostics?.let { pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) },
            )
        }
    }
}

sealed interface FeedbackStatus {
    data object Sending : FeedbackStatus
    data class Received(val reportId: String) : FeedbackStatus
    data class Failed(val reason: FeedbackFailure, val retryAfterSeconds: Long?) : FeedbackStatus
    data object Expired : FeedbackStatus
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Done(val result: UpdateResult) : UpdateState
}

/**
 * About & help (spec §9 U01, F01–F04). Owns the feedback draft, the frozen submission and the
 * manual update check. Nothing here runs on its own: every request starts from a user action.
 */
class SupportViewModel(private val app: FeiyuApp) : ViewModel() {
    private val drafts get() = app.feedbackDrafts
    private val _draft = MutableStateFlow(drafts.load())
    val draft: StateFlow<FeedbackDraft> = _draft.asStateFlow()
    private val _preview = MutableStateFlow<FeedbackPreview?>(null)
    val preview: StateFlow<FeedbackPreview?> = _preview.asStateFlow()
    private val _status = MutableStateFlow<FeedbackStatus?>(null)
    val status: StateFlow<FeedbackStatus?> = _status.asStateFlow()
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    val sending get() = _status.value == FeedbackStatus.Sending

    /** Any edit makes the next submission a new one (new id). */
    fun edit(description: String = _draft.value.description, includeDiagnostics: Boolean = _draft.value.includeDiagnostics) {
        if (sending) return
        val current = _draft.value
        if (description == current.description && includeDiagnostics == current.includeDiagnostics) return
        persist(FeedbackDraft(description, includeDiagnostics, pending = null))
        if (_status.value !is FeedbackStatus.Received) _status.value = null
    }

    /** Freezes what would be sent; an unconfirmed earlier submission is shown and resent as is. */
    fun openPreview() {
        if (sending) return
        val draft = _draft.value
        draft.pending?.let { pending ->
            _preview.value = FeedbackPreview.from(pending.submissionId, pending.body)
            return
        }
        if (!FeedbackClient.isValidDescription(draft.description)) return
        viewModelScope.launch {
            val snapshot = if (draft.includeDiagnostics) app.diagnostics.snapshot() else null
            val id = UUID.randomUUID().toString()
            _preview.value = FeedbackPreview.from(id, FeedbackClient.encode(id, draft.description, snapshot))
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    /** Sends the previewed body. Retries reuse the same id and body until the draft changes. */
    fun submit() {
        val preview = _preview.value ?: return
        if (sending) return
        val existing = _draft.value.pending
        if (existing != null && System.currentTimeMillis() - existing.createdAtMillis > FeedbackDraftStore.PENDING_TTL_MILLIS) {
            persist(_draft.value.copy(pending = null))
            _preview.value = null
            _status.value = FeedbackStatus.Expired
            return
        }
        if (existing == null) persist(_draft.value.copy(pending = PendingSubmission(preview.submissionId, preview.body, System.currentTimeMillis())))
        _preview.value = null
        _status.value = FeedbackStatus.Sending
        viewModelScope.launch {
            val started = System.nanoTime()
            val result = app.submitFeedback(preview.body)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            when (result) {
                is FeedbackResult.Received -> {
                    app.diagnostics.record(DiagnosticOperation.FEEDBACK_SUBMIT, DiagnosticResult.OK, durationMs = elapsed)
                    withContext(Dispatchers.IO) { drafts.clear() }
                    _draft.value = FeedbackDraft()
                    _status.value = FeedbackStatus.Received(result.reportId)
                }
                is FeedbackResult.Failed -> {
                    app.diagnostics.record(DiagnosticOperation.FEEDBACK_SUBMIT, DiagnosticResult.FAILED, errorKind(result.reason), durationMs = elapsed)
                    // Content problems need an edit; a new id follows from that edit.
                    if (result.reason in setOf(FeedbackFailure.REJECTED, FeedbackFailure.TOO_LARGE, FeedbackFailure.CONFLICT)) {
                        persist(_draft.value.copy(pending = null))
                    }
                    _status.value = FeedbackStatus.Failed(result.reason, result.retryAfterSeconds)
                }
            }
        }
    }

    fun discard() {
        if (sending) return
        drafts.clear()
        _draft.value = FeedbackDraft()
        _preview.value = null
        _status.value = null
    }

    fun recordShare(ok: Boolean) {
        app.diagnostics.record(DiagnosticOperation.FEEDBACK_SHARE, if (ok) DiagnosticResult.OK else DiagnosticResult.FAILED)
    }

    fun checkUpdate() {
        if (_update.value == UpdateState.Checking) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val language = AppLanguage.code(AppLanguage.context(app).resources.configuration.locales[0].language)
            val result = app.checkUpdate(UpdateClient.installedVersionCode(app), android.os.Build.VERSION.SDK_INT, language)
            app.diagnostics.record(
                DiagnosticOperation.UPDATE_CHECK,
                if (result is UpdateResult.Failed) DiagnosticResult.FAILED else DiagnosticResult.OK,
                (result as? UpdateResult.Failed)?.let { if (it.reason == com.feiyu.notes.support.UpdateFailure.TIMEOUT) ErrorKind.TIMEOUT else if (it.reason == com.feiyu.notes.support.UpdateFailure.NETWORK) ErrorKind.NETWORK else ErrorKind.BAD_RESPONSE },
                (result as? UpdateResult.Failed)?.httpStatus,
            )
            _update.value = UpdateState.Done(result)
        }
    }

    private fun persist(draft: FeedbackDraft) {
        _draft.value = draft
        drafts.save(draft)
    }

    private fun errorKind(reason: FeedbackFailure) = when (reason) {
        FeedbackFailure.OFFLINE -> ErrorKind.NETWORK
        FeedbackFailure.UNCONFIRMED -> ErrorKind.TIMEOUT
        FeedbackFailure.RATE_LIMITED -> ErrorKind.QUOTA
        FeedbackFailure.UNAVAILABLE -> ErrorKind.SERVER
        FeedbackFailure.REJECTED, FeedbackFailure.TOO_LARGE, FeedbackFailure.CONFLICT -> ErrorKind.INVALID
        FeedbackFailure.BAD_RESPONSE -> ErrorKind.BAD_RESPONSE
    }
}
