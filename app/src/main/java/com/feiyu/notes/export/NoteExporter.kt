package com.feiyu.notes.export

import com.feiyu.notes.math.MathText

/**
 * One note as a static, script-free HTML page (spec §7). Entry IDs are not exported.
 * Pure function: no access to keys, storage or other notebooks.
 */
object NoteExporter {
    fun renderNote(notebookName: String, lessonTitle: String, noteText: String, exportedOn: String, language: String = "zh",
                   formulaImage: (String) -> String? = { null }): String {
        val title = escape("$notebookName · $lessonTitle")
        val source = stripEntryIds(noteText)
        val parts = MathText.parse(source)
        val body = parts.joinToString("") { part ->
            val image = (part as? MathText.Formula)?.let { formulaImage(it.latex) }
                ?.takeIf { it.startsWith("data:image/png;base64,") }
            if (image == null) escape(part.raw)
            else {
                val tag = "<img src=\"${escape(image)}\" alt=\"${escape(part.raw)}\" title=\"${escape(part.raw)}\">"
                if ((part as MathText.Formula).display) "<div class=\"math\">$tag</div>" else tag
            }
        }
        val original = if (parts.any { it is MathText.Formula })
            "<details><summary>${if (language == "zh") "LaTeX 原文" else "LaTeX source"}</summary><pre>${escape(source)}</pre></details>" else ""
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
            main { white-space: pre-wrap; overflow-wrap: anywhere; }
            main img { zoom: .5; vertical-align: middle; max-width: 200%; }
            .math { overflow-x: auto; margin: 12px 0; }
            .math img { max-width: none; }
            details { margin-top: 24px; } pre { white-space: pre-wrap; overflow-wrap: anywhere; }
            @media (prefers-color-scheme: dark) { body { background: #121212; color: #e6e6e6; } .meta { color: #aaa; } main img { filter: invert(1) hue-rotate(180deg); } }
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
            $original
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
