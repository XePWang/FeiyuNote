package com.feiyu.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.study.StartResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in end-to-end check against the real DeepSeek API with the key saved in the app's settings.
 * Runs inside the app process on its real database, so results stay visible in the UI afterwards.
 * Skipped unless run with `-e realApi true`. Photos are staged in files/smoke via `adb shell run-as`
 * and copied to where the camera would write them; the report is written next to them.
 */
@RunWith(AndroidJUnit4::class)
class RealApiSmokeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FeiyuApp
    private val report = StringBuilder()

    @Test fun photoQuestionsFollowUpExpandAndSummary() = runBlocking {
        assumeTrue("pass -e realApi true to run", args.getString("realApi") == "true")
        assumeTrue("API key must be configured in the app", app.apiSettings.hasKey())
        val store = app.store
        val generator = app.generator
        val smokeDir = File(app.filesDir, "smoke")

        // Reuse the notebook from earlier runs instead of piling up copies.
        val course = store.listNotebooks().firstOrNull { it.name == "真实API验证" }
            ?: store.createNotebook(NotebookKind.COURSE, "真实API验证")!!
        val lesson = store.createLesson(course.id, "基础验证 ${java.time.LocalDate.now()}")!!

        fun photo(name: String): String {
            val target = app.photos.allocatePhoto(course.id)
            File(smokeDir, name).copyTo(target)
            return target.name
        }

        suspend fun run(label: String, question: Entry): Entry {
            val started = generator.ask(course.id, question)
            assertEquals("$label start", StartResult.Started, started)
            withTimeout(240_000) { generator.status.first { it.running == null } }
            val entries = store.readEntries(lesson.id, includeArchived = true)
            val user = entries.last { it.kind == EntryKind.USER }
            val reply = entries.last { it.kind == EntryKind.ASSISTANT && it.parentEntryId == user.id }
            report.append("===== $label =====\n问：${user.text.ifBlank { "（仅照片）" }}\n状态：${reply.state}\n答：\n${reply.text}\n\n")
            return reply
        }

        try {
            // 1. Photo only: built-in "识别并讲解".
            val a1 = run("1 控制系统例题（仅照片）", Entry(0, lesson.id, EntryKind.USER, EntryAction.ASK, "", imagePath = photo("control.webp")))
            // 2. Photo + text.
            val a2 = run("2 黑板（照片+文字）", Entry(0, lesson.id, EntryKind.USER, EntryAction.ASK, "黑板上讲的是什么概念？请用一个 3x3 的具体例子说明。", imagePath = photo("blackboard.webp")))
            // 3. Follow-up on #1 with the original photo attached from the chain.
            val root1 = store.getEntry(a1.parentEntryId!!)!!
            val a3 = run("3 追问并附原图", Entry(0, lesson.id, EntryKind.USER, EntryAction.ASK, "根据图(b)的超调量和峰值时间，具体算出 ξ、ωn，以及 K1、K2、a 的数值。", parentEntryId = a1.id, attachedImageEntryIds = listOf(root1.id)))
            // 4. Expand on #2, text only.
            val a4 = run("4 展开讲解", Entry(0, lesson.id, EntryKind.USER, EntryAction.EXPAND, "", parentEntryId = a2.id))
            listOf(a1, a2, a3, a4).forEach { assertEquals(EntryState.COMPLETE, it.state) }

            // 5. Summary: text only, note created on success.
            assertEquals(StartResult.Started, generator.summarize(course.id, lesson.id, null))
            val status = withTimeout(240_000) { generator.status.first { it.running == null } }
            val note = store.readEntries(lesson.id).lastOrNull { it.kind == EntryKind.NOTE }
            report.append("===== 5 整理本课 =====\n状态：${status.message}\n${note?.text ?: "（无笔记）"}\n")
            assertTrue("summary note created", note != null && note.text.isNotBlank())
        } finally {
            File(smokeDir, "report.txt").writeText(report.toString())
        }
    }
}
