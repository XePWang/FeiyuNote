package com.feiyu.notes.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database for every notebook (spec §5).
 * Foreign keys cascade lesson/notebook deletes and delete a thread's whole subtree
 * through parent_entry_id. template_id and source/attached ID lists deliberately have
 * no foreign key so the UI can show "已删除" for dangling references.
 */
class NotebookDatabase(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                instruction TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE notebooks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL CHECK (kind IN ('course', 'practice')),
                name TEXT NOT NULL,
                linked_course_id INTEGER REFERENCES notebooks(id) ON DELETE SET NULL,
                default_template_id INTEGER REFERENCES templates(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE lessons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK (kind IN ('user', 'assistant', 'note')),
                action TEXT,
                text TEXT NOT NULL,
                parent_entry_id INTEGER REFERENCES entries(id) ON DELETE CASCADE,
                source_entry_ids TEXT NOT NULL DEFAULT '[]',
                image_path TEXT,
                attached_image_entry_ids TEXT NOT NULL DEFAULT '[]',
                template_id INTEGER,
                state TEXT,
                archived INTEGER NOT NULL DEFAULT 0,
                mastery TEXT,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL("CREATE INDEX idx_lessons_notebook ON lessons(notebook_id)")
        db.execSQL("CREATE INDEX idx_entries_lesson ON entries(lesson_id)")
        db.execSQL("CREATE INDEX idx_entries_parent ON entries(parent_entry_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First schema version; add migrations here when VERSION increases.
    }

    companion object {
        const val NAME = "notes.db"
        const val VERSION = 1
    }
}
