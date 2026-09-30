package com.feiyu.notes.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Only the first system language decides; secondary preferred languages do not change this rule. */
object AppLanguage {
    fun code(language: String): String = if (language.equals("zh", ignoreCase = true)) "zh" else "en"

    fun context(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(code(config.locales[0].language)))
        return base.createConfigurationContext(config)
    }
}
