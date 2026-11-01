package io.github.ferasbusiness666.segno.ui.common

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import io.github.ferasbusiness666.segno.R

/**
 * A cover picture with rounded corners. The corners come from the view's outline, which the
 * GPU clips for free, instead of a mask drawn in software.
 */
class ArtView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    AppCompatImageView(context, attrs, defStyle) {

    var radius: Float = 0f
        set(value) {
            field = value
            invalidateOutline()
        }

    /** Round (artists) instead of a rounded square. */
    var circle: Boolean = false
        set(value) {
            field = value
            invalidateOutline()
        }

    /** Height follows width (grid covers). */
    private val square: Boolean

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ArtView)
        radius = a.getDimension(R.styleable.ArtView_artRadius, resources.getDimension(R.dimen.r_sm))
        circle = a.getBoolean(R.styleable.ArtView_artCircle, false)
        square = a.getBoolean(R.styleable.ArtView_artSquare, false)
        a.recycle()
        scaleType = ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (circle) {
                    outline.setOval(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (square) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
