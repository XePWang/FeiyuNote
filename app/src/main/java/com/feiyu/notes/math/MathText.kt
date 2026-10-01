package com.feiyu.notes.math

/** Keeps original delimiters so editing, copying and model context remain lossless. */
object MathText {
    sealed interface Part { val raw: String }
    data class Plain(override val raw: String) : Part
    data class Formula(override val raw: String, val latex: String, val display: Boolean) : Part

    fun parse(text: String): List<Part> {
        val result = mutableListOf<Part>()
        var start = 0
        var i = 0
        fun escaped(at: Int): Boolean {
            var n = 0
            var j = at - 1
            while (j >= 0 && text[j--] == '\\') n++
            return n % 2 == 1
        }
        while (i < text.length) {
            if (text[i] == '`' && !escaped(i)) {
                val run = text.substring(i).takeWhile { it == '`' }
                val end = text.indexOf(run, i + run.length)
                i = if (end < 0) text.length else end + run.length
                continue
            }
            val pair = when {
                escaped(i) -> null
                text.startsWith("$$", i) -> "$$" to "$$"
                text.startsWith("\\[", i) -> "\\[" to "\\]"
                text.startsWith("\\(", i) -> "\\(" to "\\)"
                text[i] == '$' && text.getOrNull(i + 1)?.isWhitespace() == false -> "$" to "$"
                else -> null
            }
            if (pair == null) { i++; continue }
            val (open, close) = pair
            val display = open == "$$" || open == "\\["
            var end = text.indexOf(close, i + open.length)
            while (end >= 0 && (escaped(end) || (open == "$" &&
                    (text[end - 1].isWhitespace() || text.getOrNull(end + 1)?.isDigit() == true)))) {
                end = text.indexOf(close, end + close.length)
            }
            if (end < 0 || (!display && '\n' in text.substring(i, end))) { i += open.length; continue }
            val latex = text.substring(i + open.length, end).trim()
            if (latex.isEmpty()) { i = end + close.length; continue }
            if (start < i) result += Plain(text.substring(start, i))
            result += Formula(text.substring(i, end + close.length), latex, display)
            i = end + close.length
            start = i
        }
        if (start < text.length) result += Plain(text.substring(start))
        return result
    }

    // These commands mutate the engine globally or load non-mathematical content.
    private val commands = Regex("\\\\([A-Za-z]+)")
    private val forbidden = setOf("newcommand", "renewcommand", "providecommand", "newenvironment",
        "renewenvironment", "DeclareMathOperator", "DeclareMathSizes", "definecolor", "def", "edef",
        "gdef", "xdef", "let", "csname", "includegraphics", "input", "include")

    fun canRender(latex: String): Boolean {
        if (latex.length > 4096 || commands.findAll(latex).any { it.groupValues[1] in forbidden || it.groupValues[1].startsWith("jlm") }) return false
        var depth = 0
        for (c in latex) {
            if (c == '{' && ++depth > 64) return false
            if (c == '}') depth--
        }
        // Bound explicit array repetition/column spans before the engine expands them.
        return Regex("(?:\\*|\\\\multicolumn)\\s*\\{\\s*(\\d+)\\s*\\}").findAll(latex)
            .none { (it.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE) > 32 }
    }
}
