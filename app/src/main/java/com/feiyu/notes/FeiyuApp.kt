package com.feiyu.notes

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.feiyu.notes.ai.AiConfig
import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.ai.AiReply
import com.feiyu.notes.ai.DeepSeekClient
import com.feiyu.notes.data.NotebookDatabase
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.PhotoFiles
import com.feiyu.notes.settings.ApiSettings
import com.feiyu.notes.settings.AppPrefs
import com.feiyu.notes.study.Generator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sole holder of app-scoped singletons, built by hand (no DI framework). */
class FeiyuApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var apiSettings: ApiSettings
        private set

    lateinit var avatars: com.feiyu.notes.settings.AvatarFiles
        private set
    lateinit var welcomeImage: com.feiyu.notes.settings.AvatarFiles
        private set
    lateinit var chatImage: com.feiyu.notes.settings.AvatarFiles
        private set
    lateinit var photos: PhotoFiles
        private set
    lateinit var store: NotebookStore
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var generator: Generator
        private set

    override fun onCreate() {
        super.onCreate()
        ru.noties.jlatexmath.JLatexMathAndroid.init(this)
        installProduction()
    }

    private fun installProduction() {
        apiSettings = ApiSettings(this)
        install(
            filesRoot = filesDir,
            databaseName = NotebookDatabase.NAME,
            prefsName = AppPrefs.NAME,
            loadConfig = { lessonId -> withContext(Dispatchers.IO) { apiSettings.load()?.let { forSession(lessonId, it) } } },
            generate = { config, input -> DeepSeekClient(config).generate(input) },
        )
    }

    /**
     * Test-only: swaps every singleton for an isolated set (own database, image root, prefs and a fake
     * model) so UI tests never touch the user's data or the real API. Call before launching an activity.
     */
    @VisibleForTesting
    fun installTestEnvironment(root: File, generate: suspend (AiConfig, AiInput) -> AiReply) {
        apiSettings = ApiSettings(this, TEST_API_PREFS, TEST_API_ALIAS)
        install(root, TEST_DATABASE, TEST_PREFS, loadConfig = { forSession(it, AiConfig(apiKey = "test", model = apiSettings.model(), effort = apiSettings.effort())) }, generate = generate)
    }

    /** Settings hold the default model/effort; a session's own choice overrides both. */
    private fun forSession(lessonId: Long, config: AiConfig): AiConfig {
        val choice = prefs.sessionModel(lessonId, com.feiyu.notes.ai.ModelChoice(config.model, config.effort))
        return config.copy(model = choice.model, effort = choice.effort)
    }

    @VisibleForTesting
    fun restoreProductionEnvironment() = installProduction()

    private fun install(
        filesRoot: File,
        databaseName: String,
        prefsName: String,
        loadConfig: suspend (Long) -> AiConfig?,
        generate: suspend (AiConfig, AiInput) -> AiReply,
    ) {
        avatars = com.feiyu.notes.settings.AvatarFiles(filesRoot)
        welcomeImage = com.feiyu.notes.settings.AvatarFiles(filesRoot, "welcome.png")
        chatImage = com.feiyu.notes.settings.AvatarFiles(filesRoot, "chat.png")
        photos = PhotoFiles(filesRoot)
        store = NotebookStore(NotebookDatabase(this, databaseName), photos)
        prefs = AppPrefs(this, prefsName)
        // No request survives the process, so leftover pending replies become interrupted first.
        val recovery: Job = appScope.launch { store.markPendingInterrupted() }
        generator = Generator(this, store, photos, loadConfig, appScope, ready = recovery, generate = generate, prompt = prefs::prompt)
    }

    companion object {
        const val TEST_DATABASE = "ui-test.db"
        const val TEST_PREFS = "ui_test_prefs"
        const val TEST_API_PREFS = "ui_test_api_settings"
        const val TEST_API_ALIAS = "feiyu_ui_test_api_key"
    }
}

val Context.app: FeiyuApp get() = applicationContext as FeiyuApp
