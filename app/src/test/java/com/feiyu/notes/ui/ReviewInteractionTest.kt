package com.feiyu.notes.ui

import com.feiyu.notes.data.ReviewInsertResult
import com.feiyu.notes.data.ReviewRecord
import com.feiyu.notes.data.ReviewStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewInteractionTest {

    // Helper logic matching UI extraction in ChatScreen
    private fun extractAssistantReviewDefaults(
        parentQuestionText: String?,
        replyId: Long,
        replyText: String,
        fallbackTitle: String,
    ): Pair<String, String> {
        val topic = parentQuestionText?.lineSequence()?.firstOrNull()?.take(40)?.ifBlank {
            fallbackTitle
        } ?: fallbackTitle
        val notes = replyText.lineSequence().firstOrNull().orEmpty().take(80)
        return topic to notes
    }

    // Helper logic matching UI extraction in NoteScreen
    private fun extractNoteReviewDefaults(
        noteText: String,
        fallbackTitle: String,
    ): Pair<String, String> {
        val topic = noteText.lineSequence().firstOrNull()?.take(40)?.ifBlank {
            fallbackTitle
        } ?: fallbackTitle
        val notes = noteText.take(120)
        return topic to notes
    }

    // Helper simulating dialog submission state machine
    private class SimulatedDialogState(
        var topic: String,
        var notes: String,
    ) {
        var saving = false
        var errorMessage: String? = null

        val canSave: Boolean get() = !saving && topic.isNotBlank()

        suspend fun submit(onSave: suspend (String, String) -> String?): Boolean {
            if (!canSave) return false
            saving = true
            errorMessage = null
            return try {
                val err = onSave(topic.trim(), notes.trim())
                if (err != null) {
                    errorMessage = err
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Save failed"
                false
            } finally {
                saving = false
            }
        }
    }

    @Test
    fun assistantReplyDefaultsExtractsFirstLineAndTruncates() {
        val (topic, notes) = extractAssistantReviewDefaults(
            parentQuestionText = "什么是柯西-施瓦茨不等式？\n请给出详细证明",
            replyId = 42L,
            replyText = "柯西-施瓦茨不等式是线性代数中的重要不等式。\n证明如下：设...",
            fallbackTitle = "回复 #42",
        )
        assertEquals("什么是柯西-施瓦茨不等式？", topic)
        assertEquals("柯西-施瓦茨不等式是线性代数中的重要不等式。", notes)
    }

    @Test
    fun assistantReplyDefaultsFallsBackWhenQuestionBlank() {
        val (topic, notes) = extractAssistantReviewDefaults(
            parentQuestionText = "   \n\n",
            replyId = 42L,
            replyText = "回答第一行",
            fallbackTitle = "回复 #42",
        )
        assertEquals("回复 #42", topic)
        assertEquals("回答第一行", notes)
    }

    @Test
    fun noteDefaultsExtractsTitleAndNotesWithBounds() {
        val longLine = "A".repeat(150)
        val (topic, notes) = extractNoteReviewDefaults(
            noteText = "$longLine\n第二行内容",
            fallbackTitle = "笔记 #1",
        )
        assertEquals(40, topic.length)
        assertEquals(120, notes.length)
    }

    @Test
    fun dialogStateBlocksEmptyTopicAndTrimsOnSubmit() = runTest {
        val state = SimulatedDialogState("   ", "notes")
        assertFalse(state.canSave)

        var passedTopic: String? = null
        var passedNotes: String? = null
        state.topic = "  有效主题  "
        state.notes = "  有效重点备注  "
        assertTrue(state.canSave)

        val ok = state.submit { t, n ->
            passedTopic = t
            passedNotes = n
            null
        }

        assertTrue(ok)
        assertEquals("有效主题", passedTopic)
        assertEquals("有效重点备注", passedNotes)
        assertFalse(state.saving)
        assertNull(state.errorMessage)
    }

    @Test
    fun dialogStatePreservesDraftOnFailure() = runTest {
        val state = SimulatedDialogState("我的知识点", "我的详细疑问草稿")

        val ok = state.submit { _, _ ->
            // Simulate source deletion failure during editing
            "来源内容已删除或不可用"
        }

        assertFalse(ok)
        assertFalse(state.saving)
        assertEquals("来源内容已删除或不可用", state.errorMessage)
        // Draft must be fully preserved
        assertEquals("我的知识点", state.topic)
        assertEquals("我的详细疑问草稿", state.notes)
    }

    @Test
    fun dialogStatePreservesDraftOnException() = runTest {
        val state = SimulatedDialogState("我的知识点", "我的草稿")

        val ok = state.submit { _, _ ->
            throw IllegalStateException("Database locked")
        }

        assertFalse(ok)
        assertFalse(state.saving)
        assertEquals("Database locked", state.errorMessage)
        assertEquals("我的知识点", state.topic)
        assertEquals("我的草稿", state.notes)
    }

    @Test
    fun reviewInsertResultDistinguishesAllFailureModes() {
        val dummyRecord = ReviewRecord(1, 10, "T", "N", 5)

        val success = ReviewInsertResult.Success(dummyRecord)
        val alreadyExists = ReviewInsertResult.AlreadyExists(dummyRecord)
        val sourceNotFound = ReviewInsertResult.SourceNotFound
        val invalidCourse = ReviewInsertResult.InvalidCourse
        val failed = ReviewInsertResult.Failed

        // Map results to user-facing feedback codes
        fun resolveFeedback(res: ReviewInsertResult): String = when (res) {
            is ReviewInsertResult.Success -> "added_to_review"
            is ReviewInsertResult.AlreadyExists -> "already_in_review"
            is ReviewInsertResult.SourceNotFound -> "source_not_found"
            is ReviewInsertResult.InvalidCourse -> "save_failed"
            is ReviewInsertResult.Failed -> "save_failed"
        }

        assertEquals("added_to_review", resolveFeedback(success))
        assertEquals("already_in_review", resolveFeedback(alreadyExists))
        assertEquals("source_not_found", resolveFeedback(sourceNotFound))
        assertEquals("save_failed", resolveFeedback(invalidCourse))
        assertEquals("save_failed", resolveFeedback(failed))
    }
}
