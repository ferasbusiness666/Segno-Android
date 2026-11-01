package io.github.ferasbusiness666.segno.ui.common

import android.widget.ImageView
import coil3.dispose
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.playback.MediaItems
import io.github.ferasbusiness666.segno.youtube.Thumbs

/** Loading pictures at the size they're shown. */
object Images {
    /** A song's cover, about [px] pixels wide. */
    fun track(view: ImageView, track: Track?, px: Int) {
        val url = track?.let { MediaItems.artFor(it, px) }.orEmpty()
        url(view, url, R.drawable.art_placeholder)
    }

    fun url(view: ImageView, url: String, placeholder: Int = R.drawable.art_placeholder) {
        if (url.isEmpty()) {
            view.dispose()
            view.setImageResource(placeholder)
            return
        }
        view.load(url) {
            placeholder(placeholder)
            error(placeholder)
        }
    }

    fun sized(url: String, px: Int) = Thumbs.sized(url, px)
}
