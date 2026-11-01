package io.github.ferasbusiness666.segno.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import io.github.ferasbusiness666.segno.R

/**
 * The picture shown while a cover loads, or when there's none: a dark square with a small icon
 * in the middle. The icon keeps a sensible size whether the square is a 40 dp row or a big cover.
 */
class ArtPlaceholder(context: Context, icon: Int) : Drawable() {
    private val paint = Paint().apply { color = ContextCompat.getColor(context, R.color.raised_2) }
    private val glyph = glyphFor(context, icon)
    private val min = context.dp(18)
    private val max = context.dp(72)

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, paint)
        val size = (minOf(b.width(), b.height()) * 0.36f).toInt().coerceIn(min, max)
        val l = b.centerX() - size / 2
        val t = b.centerY() - size / 2
        glyph.setBounds(l, t, l + size, t + size)
        glyph.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        glyph.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        /** Icons are read from resources once, then copied cheaply for each row. */
        private val cache = HashMap<Int, ConstantState>()

        private fun glyphFor(context: Context, icon: Int): Drawable {
            val state = cache[icon] ?: AppCompatResources.getDrawable(context, icon)!!.mutate().apply {
                setTint(ContextCompat.getColor(context, R.color.text_3))
            }.constantState!!.also { cache[icon] = it }
            return state.newDrawable(context.resources).mutate()
        }
    }
}
