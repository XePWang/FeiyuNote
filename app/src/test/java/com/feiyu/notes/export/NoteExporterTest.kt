package com.feiyu.notes.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExporterTest {
    @Test fun formulaExportEmbedsImagesAndKeepsSafeSource() {
        val html = NoteExporter.renderNote("n", "l", "Formula \\(x<y\\)\n\\[\\frac{1}{2}\\] <script>", "d", "en") {
            "data:image/png;base64,aGVsbG8="
        }
        assertTrue(html.contains("<img src=\"data:image/png;base64,"))
        assertTrue(html.contains("alt=\"\\(x&lt;y\\)\""))
        assertTrue(html.contains("<div class=\"math\">"))
        assertTrue(html.contains("LaTeX source"))
        assertFalse(html.contains("<script>"))
        val fallback = NoteExporter.renderNote("n", "l", "\\[\\bad{\\]", "d")
        assertTrue(fallback.contains("\\[\\bad{\\]"))
        assertFalse(fallback.contains("<img"))
    }

    @Test fun escapesAndHasNoScriptOrIds() {
        val html = NoteExporter.renderNote(
            notebookName = "高数<一>",
            lessonTitle = "2026-09-30",
            noteText = "Q: 1 < 2 & \"x\"?\n<script>alert(1)</script>\nA: 是的 ∑",
            exportedOn = "2026-09-30",
        )
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertTrue(html.contains("高数&lt;一&gt;"))
        assertTrue(html.contains("1 &lt; 2 &amp; &quot;x&quot;?"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("是的 ∑"))
        assertFalse(html.contains("<script"))
        assertFalse(Regex("""(src|href)\s*=""").containsMatchIn(html))
    }

    @Test fun entryIdMarkersAreStripped() {
        val html = NoteExporter.renderNote("n", "l", "Q: 极限？ [来源 #12, #13]\nA: 见【#14 答】结论", "d")
        assertFalse(html.contains("来源"))
        assertFalse(html.contains("#13"))
        assertFalse(html.contains("#14"))
        assertTrue(html.contains("Q: 极限？"))
        assertTrue(html.contains("结论"))
    }

    @Test fun fileNameIsSafe() {
        val name = NoteExporter.fileName("高数/上", "第 1 课:极限")
        assertFalse(name.contains('/') || name.contains(':') || name.contains(' '))
        assertTrue(name.endsWith(".html"))
    }
}
