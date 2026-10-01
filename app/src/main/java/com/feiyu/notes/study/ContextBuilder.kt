package com.feiyu.notes.study

import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.ai.AiMessage
import com.feiyu.notes.ai.AiRole
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.Template
import java.io.File

/**
 * Pure request assembly (spec §6). The saved user entry is the only authority for a turn:
 * action, parent, own photo, attached photos and template come from it. Callers pass the
 * already-validated reference note and template contents.
 */
object ContextBuilder {

    fun buildTurn(
        target: Entry,
        lessonEntries: List<Entry>,
        referenceNote: Entry?,
        template: Template?,
        resolvePhoto: (String) -> File,
        base: String = StudyPrompts.SYSTEM,
    ): AiInput {
        require(target.kind == EntryKind.USER && target.action != null)
        val chain = ancestors(target, lessonEntries)
        val onChain = chain.associateBy { it.id }

        val history = chain.mapNotNull { e ->
            when {
                e.kind == EntryKind.USER -> AiMessage(AiRole.USER, e.text.ifBlank { StudyPrompts.PHOTO_ONLY_PLACEHOLDER })
                e.kind == EntryKind.ASSISTANT && e.state == EntryState.COMPLETE -> AiMessage(AiRole.ASSISTANT, e.text)
                else -> null
            }
        }

        // Own photo first, then user-selected photos from this chain only.
        val images = buildList {
            addAll(target.imagePaths.map(resolvePhoto))
            target.attachedImageEntryIds.mapNotNull(onChain::get)
                .filter { it.kind == EntryKind.USER }
                .forEach { addAll(it.imagePaths.map(resolvePhoto)) }
        }

        val text = when (target.action) {
            EntryAction.ASK -> target.text.ifBlank { if (images.isNotEmpty()) StudyPrompts.IDENTIFY_AND_EXPLAIN else "" }
            EntryAction.EXPAND -> listOf(StudyPrompts.EXPAND, target.text).filter { it.isNotBlank() }.joinToString("\n")
            EntryAction.MISTAKE -> "${StudyPrompts.MISTAKE}\n我的解答：${target.text.ifBlank { StudyPrompts.PHOTO_ONLY_PLACEHOLDER }}"
        }

        val system = StudyPrompts.withTemplate(
            StudyPrompts.withReference(base, referenceNote?.text),
            template?.instruction,
        )
        return AiInput(system, history + AiMessage(AiRole.USER, text, images))
    }

    /**
     * Summary of a lesson's completed Q&A, text only (spec §6). [lessonEntries] must already
     * exclude archived threads. Returns null when there is nothing completed to summarize.
     */
    fun buildSummary(lessonEntries: List<Entry>, template: Template?, base: String = PromptKind.SUMMARY.default): Pair<AiInput, List<Long>>? {
        val answered = lessonEntries.filter { it.kind == EntryKind.ASSISTANT && it.state == EntryState.COMPLETE }
        if (answered.isEmpty()) return null
        val byId = lessonEntries.associateBy { it.id }
        val lines = mutableListOf<String>()
        val sources = mutableListOf<Long>()
        for (answer in answered) {
            val question = answer.parentEntryId?.let(byId::get) ?: continue
            val parentNote = question.parentEntryId?.let { "，追问自 #$it" }.orEmpty()
            lines += "[#${question.id} 问$parentNote] ${question.text.ifBlank { StudyPrompts.PHOTO_ONLY_PLACEHOLDER }}"
            lines += "[#${answer.id} 答] ${answer.text}"
            sources += listOf(question.id, answer.id)
        }
        if (sources.isEmpty()) return null
        val system = StudyPrompts.withTemplate(base, template?.instruction)
        val input = AiInput(system, listOf(AiMessage(AiRole.USER, "本课问答如下：\n" + lines.joinToString("\n"))))
        return input to sources.distinct()
    }

    /** Entries from the thread root down to (excluding) [target]. */
    fun ancestors(target: Entry, lessonEntries: List<Entry>): List<Entry> {
        val byId = lessonEntries.associateBy { it.id }
        val chain = ArrayDeque<Entry>()
        var next = target.parentEntryId?.let(byId::get)
        while (next != null && chain.size < lessonEntries.size) {
            chain.addFirst(next)
            next = next.parentEntryId?.let(byId::get)
        }
        return chain.toList()
    }
}
