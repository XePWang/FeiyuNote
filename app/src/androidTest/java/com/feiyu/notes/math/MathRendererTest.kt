package com.feiyu.notes.math

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class MathRendererTest {
    @Test fun realEngineDrawsStandardMathAndFallsBackForInvalidInput() {
        listOf("\\frac{1}{2}", "\\sqrt{x^2+y^2}", "\\int_0^1 x^2\\,dx", "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}").forEach { latex ->
            val bitmap = MathRenderer.render(latex, 32f, Color.WHITE)
            assertNotNull(latex, bitmap)
            val pixels = IntArray(bitmap!!.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(latex, pixels.any { Color.alpha(it) > 0 })
        }
        assertNull(MathRenderer.render("\\feiyuInvalid{x}", 32f, Color.BLACK))
        assertNull(MathRenderer.render("\\newcommand{\\x}{\\x}\\x", 32f, Color.BLACK))
        val wide = MathRenderer.render((1..30).joinToString("+") { "x_{$it}" }, 32f, Color.BLACK)!!
        assertTrue(wide.width > 600)
        assertTrue(MathRenderer.dataUri("\\frac{1}{2}")!!.startsWith("data:image/png;base64,"))
    }
}
