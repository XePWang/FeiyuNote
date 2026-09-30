package com.feiyu.notes.export

/**
 * One note as a static, script-free HTML page (spec §7). Entry IDs are not exported.
 * Pure function: no access to keys, storage or other notebooks.
 */
object NoteExporter {
    fun renderNote(notebookName: String, lessonTitle: String, noteText: String, exportedOn: String, language: String = "zh"): String {
        val title = escape("$notebookName · $lessonTitle")
        val body = stripEntryIds(noteText).lines().joinToString("\n") { line -> if (line.isBlank()) "<br>" else "<p>${escape(line)}</p>" }
        return """
            <!DOCTYPE html>
            <html lang="${if (language == "zh") "zh-CN" else "en"}">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>
            body { max-width: 760px; margin: 0 auto; padding: 16px; font-family: sans-serif; line-height: 1.7; color: #1b1b1f; background: #fff; }
            header { border-bottom: 1px solid #ccc; margin-bottom: 16px; }
            h1 { font-size: 1.3em; margin: 0 0 4px; }
            .meta { color: #666; font-size: 0.9em; margin-bottom: 8px; }
            p { margin: 0 0 0.4em; white-space: pre-wrap; word-break: break-word; }
            @media (prefers-color-scheme: dark) { body { background: #121212; color: #e6e6e6; } .meta { color: #aaa; } }
            </style>
            </head>
            <body>
            <header>
            <h1>$title</h1>
            <div class="meta">${if (language == "zh") "导出日期：" else "Exported: "}${escape(exportedOn)}</div>
            </header>
            <main>
            $body
            </main>
            </body>
            </html>
        """.trimIndent() + "\n"
    }

    fun fileName(notebookName: String, lessonTitle: String): String =
        "$notebookName-$lessonTitle".replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(80) + ".html"

    /** Entry IDs mean nothing outside the app; drop "[来源 #12, #13]" / "[#12 问]" style markers. */
    internal fun stripEntryIds(text: String): String =
        text.replace(Regex("""[ \t]*[\[【][ \t]*(来源)?[ \t]*#\d+[^\]】\n]*[\]】]"""), "")

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
