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

    /** [kind]: R.drawable.art_placeholder, art_placeholder_playlist or art_placeholder_artist. */
    fun url(view: ImageView, url: String, kind: Int = R.drawable.art_placeholder) {
        val ph = placeholderFor(view, kind)
        if (url.isEmpty()) {
            view.dispose()
            view.setImageDrawable(ph)
            return
        }
        view.load(url) {
            placeholder(ph)
            error(ph)
        }
    }

    /** Shows the empty-cover picture. */
    fun placeholder(view: ImageView, kind: Int = R.drawable.art_placeholder) {
        view.dispose()
        view.setImageDrawable(placeholderFor(view, kind))
    }

    /** Each view keeps its own placeholder, so list rows don't make a new one on every bind. */
    private fun placeholderFor(view: ImageView, kind: Int): ArtPlaceholder {
        val icon = iconFor(kind)
        (view.getTag(R.id.tag_placeholder) as? ArtPlaceholder)?.let { if (it.icon == icon) return it }
        return ArtPlaceholder(view.context, icon).also { view.setTag(R.id.tag_placeholder, it) }
    }

    fun placeholderDrawable(view: ImageView, kind: Int = R.drawable.art_placeholder) = ArtPlaceholder(view.context, iconFor(kind))

    private fun iconFor(kind: Int) = when (kind) {
        R.drawable.art_placeholder_playlist -> R.drawable.ic_playlist
        R.drawable.art_placeholder_artist -> R.drawable.ic_artist
        else -> R.drawable.ic_music
    }

    fun sized(url: String, px: Int) = Thumbs.sized(url, px)
}
