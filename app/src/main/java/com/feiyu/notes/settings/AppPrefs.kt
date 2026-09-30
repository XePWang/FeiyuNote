package com.feiyu.notes.settings

import android.content.Context

/** Plain UI preferences; not part of the notebook database. */
class AppPrefs(context: Context, name: String = NAME) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    val lastLesson: Pair<Long, Long>?
        get() {
            val notebook = prefs.getLong(NOTEBOOK, -1)
            val lesson = prefs.getLong(LESSON, -1)
            return if (notebook > 0 && lesson > 0) notebook to lesson else null
        }

    fun setLastLesson(notebookId: Long, lessonId: Long) {
        prefs.edit().putLong(NOTEBOOK, notebookId).putLong(LESSON, lessonId).apply()
    }

    fun clearLastLesson() {
        prefs.edit().remove(NOTEBOOK).remove(LESSON).apply()
    }

    /** Pick once per lesson. Resource IDs are never persisted because they change between builds. */
    @Synchronized
    fun avatarIndex(lessonId: Long, count: Int): Int {
        require(count > 0)
        val key = "avatar_$lessonId"
        val saved = prefs.getInt(key, -1)
        if (saved in 0 until count) return saved
        return kotlin.random.Random.nextInt(count).also { prefs.edit().putInt(key, it).apply() }
    }

    companion object {
        const val NAME = "app_prefs"
        private const val NOTEBOOK = "last_notebook_id"
        private const val LESSON = "last_lesson_id"
    }
}
