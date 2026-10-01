package com.feiyu.notes.math

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.ByteArrayOutputStream

/** Shared by Compose and offline HTML export. Call off the UI thread. */
object MathRenderer {
    private data class Key(val latex: String, val size: Float, val color: Int)
    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }

    // ponytail: one lock protects JLaTeXMath's global font/parser state; replace the engine
    // with isolated instances if measured rendering contention becomes significant.
    @Synchronized
    fun render(latex: String, textSize: Float, color: Int): Bitmap? {
        if (!MathText.canRender(latex)) return null
        val key = Key(latex, textSize, color)
        cache.get(key)?.let { return it }
        return try {
            val drawable = JLatexMathDrawable.builder(latex).textSize(textSize).color(color).padding(2).build()
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w !in 1..8192 || h !in 1..2048 || w.toLong() * h > 2_000_000) return null
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                drawable.draw(Canvas(it))
                cache.put(key, it)
            }
        } catch (_: Exception) { null }
        catch (_: StackOverflowError) { null } // Malformed deeply recursive input stays readable as source.
    }

    fun dataUri(latex: String): String? {
        val bitmap = render(latex, 32f, 0xff202b3f.toInt()) ?: return null
        val bytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        }
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
