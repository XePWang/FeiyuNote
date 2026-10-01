package com.feiyu.notes.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feiyu.notes.FeiyuApp
import com.feiyu.notes.MainActivity
import com.feiyu.notes.ai.AiConfig
import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.ai.AiReply
import com.feiyu.notes.data.EntryKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * End-to-end UI flows replacing the manual emulator checks. Runs against an isolated test
 * environment (own DB, image root, prefs, fake model); camera, gallery, save and share are stubbed.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FeiyuApp
    private val localeManager = app.getSystemService(android.app.LocaleManager::class.java)
    private lateinit var originalLocales: android.os.LocaleList
    private val root = File(app.filesDir, "ui-test")
    private val inputs: MutableList<AiInput> = Collections.synchronizedList(mutableListOf())
    private val configs: MutableList<AiConfig> = Collections.synchronizedList(mutableListOf())
    private var scenario: ActivityScenario<MainActivity>? = null
    private var mathReply: String? = null

    private val fakeModel: suspend (AiConfig, AiInput) -> AiReply = { config, input ->
        configs += config
        inputs += input
        mathReply?.let { AiReply(it) } ?: if (input.systemText.contains("复习笔记")) AiReply("笔记正文\n第二行") else AiReply("答案${inputs.size}")
    }

    @Before fun setUp() {
        originalLocales = localeManager.applicationLocales
        localeManager.applicationLocales = android.os.LocaleList.forLanguageTags("zh-CN")
        app.deleteDatabase(FeiyuApp.TEST_DATABASE)
        app.getSharedPreferences(FeiyuApp.TEST_PREFS, 0).edit().clear().commit()
        app.getSharedPreferences(FeiyuApp.TEST_API_PREFS, 0).edit().clear().commit()
        root.deleteRecursively()
        app.installTestEnvironment(root, fakeModel)
        Intents.init()
        launch()
    }

    @After fun tearDown() {
        scenario?.close()
        localeManager.applicationLocales = originalLocales
        Intents.release()
        shell("wm size reset")
        app.restoreProductionEnvironment()
        app.deleteDatabase(FeiyuApp.TEST_DATABASE)
        root.deleteRecursively()
    }

    // ---- flows ----

    @Test fun formulasRenderInChatNotesAndOfflineExportWithoutChangingSource() {
        val sample = "公式笔记\n行内 \\(x^2+\\sqrt{y}\\)\n\\[\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}\\]\n" +
            "\\[\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\\]\n\\[\\int_0^1 x^2\\,dx=\\frac{1}{3}\\]"
        mathReply = sample
        createNotebook("新建课程", "公式测试")
        click("公式测试")
        click("新课次")
        shell("wm size 1080x2300")
        ask("请解释这些公式")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("math-display", useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        assertTrue(compose.onAllNodesWithTag("math-inline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val lessonId = app.prefs.lastLesson!!.second
        assertEquals(sample, runBlocking { app.store.readEntries(lessonId).single { it.kind == EntryKind.ASSISTANT }.text })
        screenshot("math-chat-phone")
        compose.onNodeWithTag("copy-math-source").performScrollTo().performClick()
        scenario!!.onActivity { activity ->
            assertEquals(sample, activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        click("整理本课")
        click("开始整理")
        waitText("公式笔记")
        click("公式笔记")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("math-display", useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        click("编辑")
        compose.onNodeWithTag("note-editor").assertExists()
        compose.onNodeWithTag("note-editor").performTextClearance()
        val edited = sample + "\n\\[\\feiyuInvalid{x}\\]"
        compose.onNodeWithTag("note-editor").performTextInput(edited)
        compose.onNodeWithText("保存").performScrollTo().performClick()
        waitText("已保存")
        compose.onNodeWithText("预览").performScrollTo().performClick()
        compose.onNodeWithText("\\[\\feiyuInvalid{x}\\]").assertExists()
        val exported = File(app.cacheDir, "exports/math-export.html").apply { parentFile!!.mkdirs(); writeText("") }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(providerUri(exported)))
        )
        compose.onNodeWithText("导出 HTML").performScrollTo().performClick()
        waitText("已导出")
        val html = exported.readText()
        assertTrue(html.contains("data:image/png;base64,") && html.contains("LaTeX 原文"))
        assertTrue(html.contains("\\feiyuInvalid{x}"))
        assertTrue(!html.contains("<script") && !html.contains("https://"))
    }

    @Test fun courseFlowAskFollowUpExpandSummarizeEditExport() {
        createNotebook("新建课程", "高数")
        click("高数")
        click("新课次")
        ask("什么是极限")
        awaitAnswer("答案1")

        clickNth("追问", 0)
        ask("再举个例子")
        awaitAnswer("答案2")
        assertEquals(listOf("什么是极限", "答案1", "再举个例子"), inputs.last().messages.map { it.text })

        clickNth("展开讲解", 0)
        compose.onNodeWithTag("send").performClick()
        awaitAnswer("答案3")

        click("整理本课")
        click("开始整理")
        awaitAnswer("笔记正文")
        assertTrue("summary is text only", inputs.last().messages.all { it.images.isEmpty() })

        // Note: edit, save, export (stubbed save dialog) and share (stubbed chooser).
        click("笔记正文")
        click("编辑")
        compose.onNodeWithTag("note-editor").performTextClearance()
        compose.onNodeWithTag("note-editor").performTextInput("改过的笔记 <b>")
        click("保存")
        waitText("已保存")
        val exported = File(app.cacheDir, "exports/test-export.html").apply { parentFile!!.mkdirs(); delete(); createNewFile() }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(providerUri(exported)))
        )
        click("导出 HTML")
        waitText("已导出")
        val html = exported.readText()
        assertTrue(html.contains("改过的笔记 &lt;b&gt;") && !html.contains("<script"))
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        click("分享")
        compose.waitForIdle()
        intended(hasAction(Intent.ACTION_CHOOSER))

        // Cold start returns to the last lesson.
        scenario?.close()
        launch()
        // Reopens the last lesson scrolled to its newest reply.
        awaitAnswer("答案3")
        compose.onNodeWithTag("composer-input").assertExists()
    }

    @Test fun cameraAndGalleryPhotosAreAttachedAndSent() {
        createNotebook("新建课程", "物理")
        click("物理")
        click("新课次")

        // Camera cancelled: no attachment, no request.
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        click("拍照")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("照片").assertDoesNotExist()

        // Camera succeeds: the stub writes a PNG to the requested output URI.
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
            val out = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
            app.contentResolver.openOutputStream(out)!!.use { testBitmap(Color.RED).compress(Bitmap.CompressFormat.PNG, 100, it) }
            Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
        click("拍照")
        waitDescription("照片")
        compose.onNodeWithTag("send").performClick()
        awaitAnswer("答案1")
        assertEquals(1, inputs.last().messages.last().images.size)

        // Multi-select includes an invalid file: retain usable copies, report the failed one.
        val picked = listOf(Color.BLUE, Color.GREEN, Color.YELLOW).mapIndexed { index, color ->
            File(app.cacheDir, "exports/picked-$index.png").apply {
                parentFile!!.mkdirs()
                outputStream().use { testBitmap(color).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val invalid = File(app.cacheDir, "exports/invalid.png").apply { writeText("not an image") }
        val clip = android.content.ClipData.newRawUri("photos", providerUri(picked.first()))
        (picked.drop(1) + invalid).forEach { clip.addItem(android.content.ClipData.Item(providerUri(it))) }
        intending(hasAction(MediaStore.ACTION_PICK_IMAGES)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { clipData = clip })
        )
        click("相册")
        waitText("3 张图片")
        waitText("1 张图片导入失败，其余已选图片已保留")
        // Activity recreation retains all draft attachments; removing one deletes only that copy.
        scenario!!.recreate()
        waitText("3 张图片")
        click("移除第 2 张")
        waitText("2 张图片")
        click("拍照")
        waitText("3 张图片")
        intending(hasAction(MediaStore.ACTION_PICK_IMAGES)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        click("相册")
        waitText("3 张图片")
        shell("wm size 1080x2300")
        compose.waitForIdle()
        screenshot("multi-photo-phone")
        ask("这些图片是什么颜色")
        awaitAnswer("答案2")
        val sent = inputs.last().messages.last()
        assertEquals(3, sent.images.size)
        assertTrue(sent.images.all { it.path.startsWith(File(root, "images").path) })
        assertEquals(listOf(Color.BLUE, Color.YELLOW, Color.RED), sent.images.map {
            android.graphics.BitmapFactory.decodeFile(it.path).let { bitmap -> bitmap.getPixel(0, 0).also { bitmap.recycle() } }
        })
        val photos = runBlocking { app.store.readEntries(app.prefs.lastLesson!!.second) }
            .filter { it.kind == EntryKind.USER }.flatMap { it.imagePaths }
        assertEquals(4, photos.size)
        assertEquals("failed/removed copies cleaned up", 4, File(root, "images").walkTopDown().count { it.isFile })
    }

    @Test fun practiceBookMasteryAndMistakeOnlyInPractice() {
        createNotebook("新建课程", "线代")
        createNotebook("新建刷题本", "线代错题", linkCourse = "线代")
        compose.onNodeWithText("关联课程：线代").assertExists()

        click("线代错题")
        click("新章节/试卷")
        ask("求矩阵的逆")
        awaitAnswer("答案1")
        click("未掌握")
        waitText("已掌握")
        clickNth("错题讲解", 0)
        ask("我算出来是单位阵")
        awaitAnswer("答案2")
        assertTrue(inputs.last().messages.last().text.contains("我算出来是单位阵"))

        // A course lesson has neither mastery nor the mistake action.
        systemBack()
        systemBack()
        click("线代")
        click("新课次")
        ask("行列式")
        awaitAnswer("答案3")
        compose.onNodeWithText("错题讲解").assertDoesNotExist()
        compose.onNodeWithText("未掌握").assertDoesNotExist()
    }

    @Test fun defaultTemplateAppliesToQuestionsNotSummaries() {
        click("设置")
        scenario!!.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        click("讲解模板")
        click("新建")
        compose.onNodeWithTag("text-input").performTextInput("严格")
        compose.onNodeWithTag("template-instruction").performTextInput("每步写出依据")
        click("确定")
        waitText("严格")
        systemBack()
        systemBack()

        createNotebook("新建课程", "概率", defaultTemplate = "严格")
        click("概率")
        click("新课次")
        ask("什么是条件概率")
        awaitAnswer("答案1")
        assertTrue(inputs.last().systemText.contains("每步写出依据"))
        click("整理本课")
        click("开始整理")
        awaitAnswer("笔记正文")
        assertTrue("default template not used for summaries", !inputs.last().systemText.contains("每步写出依据"))
    }

    @Test fun archiveRestoreAndDeleteThread() {
        createNotebook("新建课程", "化学")
        click("化学")
        click("新课次")
        ask("第一问")
        awaitAnswer("答案1")
        ask("第二问")
        awaitAnswer("答案2")

        compose.onNodeWithTag("chat-list").performScrollToIndex(1)
        compose.onAllNodesWithTag("thread-menu")[0].performClick()
        click("归档")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第一问").fetchSemanticsNodes().isEmpty() }
        click("已归档")
        waitText("第一问")
        compose.onAllNodesWithTag("thread-menu")[0].performClick()
        click("恢复")
        systemBack()
        waitText("第一问")

        compose.onNodeWithTag("chat-list").performScrollToIndex(1)
        compose.onAllNodesWithTag("thread-menu")[0].performClick()
        click("删除整条问答")
        click("删除")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第一问").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("第二问").assertExists()
    }

    @Test fun layoutAdaptsToWindowWidthAndKeepsDraft() {
        createNotebook("新建课程", "英语")
        click("英语")
        click("新课次")
        compose.onNodeWithTag("composer-input").performTextInput("草稿不丢")

        shell("wm size 2076x2152")
        waitText("新课次") // list pane visible next to the lesson
        compose.onNodeWithTag("composer-input").assertExists()

        shell("wm size 1080x2300")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("新课次").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction() and hasText("草稿不丢")).assertExists()
    }

    @Test fun sessionAvatarAndCustomPhotoSurviveRecreation() {
        createNotebook("新建课程", "头像测试")
        click("头像测试")
        click("新课次")
        ask("头像保持一致")
        awaitAnswer("答案1")
        waitDescription("DeepSeek 头像")
        val lesson = app.prefs.lastLesson!!.second
        val index = app.prefs.avatarIndex(lesson, WhalePortraits.size)
        scenario!!.recreate()
        waitDescription("DeepSeek 头像")
        assertEquals(index, app.prefs.avatarIndex(lesson, WhalePortraits.size))
        systemBack()
        systemBack()
        click("设置")
        val picked = File(app.cacheDir, "exports/avatar.png").apply { parentFile!!.mkdirs() }
        picked.outputStream().use { testBitmap(Color.CYAN).compress(Bitmap.CompressFormat.PNG, 100, it) }
        intending(hasAction(MediaStore.ACTION_PICK_IMAGES)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(providerUri(picked)))
        )
        compose.onNodeWithTag("choose-avatar").performScrollTo().performClick()
        waitText("头像已更新")
        assertTrue(app.avatars.file.isFile)
        scenario!!.recreate()
        compose.onNodeWithTag("reset-avatar").performScrollTo().performClick()
        waitText("已恢复每个课次的随机头像")
        assertTrue(!app.avatars.file.exists())
        assertEquals(index, app.prefs.avatarIndex(lesson, WhalePortraits.size))
    }

    @Test fun nonChineseLanguageUsesEnglishAndChineseUsesChinese() {
        // A German primary language must not pick Chinese from the secondary preference.
        scenario!!.close()
        localeManager.applicationLocales = android.os.LocaleList.forLanguageTags("de-DE,zh-CN")
        launch()
        waitText("Feiyu Notes")
        click("New course")
        compose.onNodeWithTag("notebook-name").performTextInput("Calculus")
        click("Confirm")
        click("Calculus")
        click("New session")
        ask("Explain a limit")
        awaitAnswer("答案1")
        assertTrue(inputs.last().systemText.contains("Respond in English"))
        compose.onNodeWithTag("send").assertIsDisplayed()
        shell("wm size 1080x2300")
        screenshot("chat-en-phone")
        scenario!!.close()
        localeManager.applicationLocales = android.os.LocaleList.forLanguageTags("zh-TW")
        launch()
        waitText("发送")
        waitDescription("DeepSeek 头像")
        screenshot("chat-zh-phone")
    }

    @Test fun generalChatPairsNumbersJumpsWithoutRequestsAndUsesSessionEffort() {
        click("公共聊天")
        waitText("deepseek-flash · Low")
        ask("第一问")
        awaitAnswer("答案1")
        ask("第二问")
        awaitAnswer("答案2")
        // The second message continues the first answer, with the general prompt and full history.
        val entries = runBlocking { app.store.readEntries(com.feiyu.notes.data.NotebookStore.GENERAL_ID) }
        assertEquals(entries.first { it.kind == EntryKind.ASSISTANT }.id, entries.last { it.kind == EntryKind.USER }.parentEntryId)
        assertEquals(3, inputs.last().messages.size)
        assertTrue(inputs.last().systemText.contains("通用助手"))
        assertEquals(listOf("deepseek-flash" to "low", "deepseek-flash" to "low"), configs.map { it.model to it.effort })
        listOf("提问 #1", "DeepSeek · 回答 #1", "追问 #2", "DeepSeek · 回答 #2").forEach(::waitText)

        compose.onNodeWithTag("composer-input").performTextInput("草稿")
        compose.onNodeWithContentDescription("跳到提问 #1：第一问").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assert(hasText("草稿"))
        assertEquals(2, inputs.size)

        app.prefs.setSessionModel(com.feiyu.notes.data.NotebookStore.GENERAL_ID, com.feiyu.notes.ai.ModelChoice("deepseek-flash", "high"))
        waitText("deepseek-flash · High")
        compose.onNodeWithTag("send").performClick()
        hideKeyboard()
        awaitAnswer("答案3")
        assertEquals("high", configs.last().effort)
        screenshot("general-chat-phone")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Compose idleness does not include the system Activity/window transition.
        Thread.sleep(800)
        val dir = File(app.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    // ---- helpers ----

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun createNotebook(button: String, name: String, linkCourse: String? = null, defaultTemplate: String? = null) {
        click(button)
        compose.onNodeWithTag("notebook-name").performTextInput(name)
        linkCourse?.let { clickLast(it) } // the dialog's chip, not the list row behind it
        defaultTemplate?.let { click(it) }
        click("确定")
        waitText(name)
    }

    private fun ask(text: String) {
        compose.onNodeWithTag("composer-input").performTextInput(text)
        compose.onNodeWithTag("send").performClick()
        hideKeyboard()
    }

    private fun click(text: String) {
        waitText(text)
        compose.onAllNodes(hasText(text) or hasContentDescription(text))[0].performClick()
        compose.waitForIdle()
    }

    private fun clickLast(text: String) {
        waitText(text)
        compose.onAllNodesWithText(text).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitForIdle()
    }

    /** A real BACK key event, routed like the system back gesture. */
    private fun systemBack() {
        hideKeyboard()
        Espresso.pressBack()
        compose.waitForIdle()
    }

    /** Read answers with the IME dismissed, independent of the AVD's keyboard preference. */
    private fun hideKeyboard() {
        scenario!!.onActivity { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            var visible = false
            scenario!!.onActivity { activity ->
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            !visible
        }
        compose.waitForIdle()
    }

    /** The reply is on screen and the single generation slot is free again. */
    private fun awaitAnswer(text: String) {
        waitText(text)
        compose.waitUntil(10_000) { app.generator.status.value.running == null }
    }

    private fun clickNth(text: String, index: Int) {
        waitText(text)
        compose.onAllNodesWithText(text)[index].performClick()
        compose.waitForIdle()
    }

    private fun waitText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitDescription(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() }

    private fun providerUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.photos", file)

    private fun testBitmap(color: Int): Bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(1_500)
        compose.waitForIdle()
    }
}
