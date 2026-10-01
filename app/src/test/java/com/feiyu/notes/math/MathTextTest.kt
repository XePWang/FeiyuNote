package com.feiyu.notes.math

import org.junit.Assert.*
import org.junit.Test

class MathTextTest {
    @Test fun delimitersPreserveSourceAndDisplayMode() {
        val source = "行内 ${'$'}x^2${'$'}，\\(\\sqrt{2}\\)。\n${'$'}${'$'}\\frac{1}{2}\n+3${'$'}${'$'}\n\\[\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\\]"
        val parts = MathText.parse(source)
        assertEquals(source, parts.joinToString("") { it.raw })
        assertEquals(listOf(false, false, true, true), parts.filterIsInstance<MathText.Formula>().map { it.display })
        assertEquals("x^2", parts.filterIsInstance<MathText.Formula>().first().latex)
    }

    @Test fun currencyEscapesCodeAndUnclosedInputStayLiteral() {
        listOf("Price ${'$'}5 and ${'$'}10", "\\${'$'}x\\${'$'}", "`${'$'}x${'$'}`", "```\n\\[x\\]\n```", "\\[unfinished", "${'$'}x\ny${'$'}").forEach {
            val parts = MathText.parse(it)
            assertTrue(it, parts.none { part -> part is MathText.Formula })
            assertEquals(it, parts.joinToString("") { part -> part.raw })
        }
    }

    @Test fun definitionsResourcesAndUnboundedExpansionAreNotRendered() {
        assertTrue(MathText.canRender("\\frac{1}{2}+\\int_0^1 x^2 dx"))
        listOf("\\newcommand{\\x}{\\x}\\x", "\\DeclareMathOperator{\\foo}{foo}", "\\jlmExternalFont{x}",
            "\\includegraphics{/private/file}", "\\begin{array}{*{999999}{c}}x\\end{array}",
            "{".repeat(65), "x".repeat(4097)).forEach { assertFalse(it, MathText.canRender(it)) }
    }
}
