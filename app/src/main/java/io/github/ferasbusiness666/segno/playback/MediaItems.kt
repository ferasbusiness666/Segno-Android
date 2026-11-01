package io.github.ferasbusiness666.segno.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.youtube.Thumbs

/**
 * Songs as the player sees them. Online songs get a "segno://" address that [SegnoDataSource]
 * turns into audio when the song is about to play.
 */
object MediaItems {
    const val SCHEME = "segno"

    fun uriFor(t: Track): Uri = when (t.kind) {
        Track.Kind.YouTube -> Uri.Builder().scheme(SCHEME).authority("yt").appendPath(t.key).build()
        Track.Kind.Spotify -> Uri.Builder().scheme(SCHEME).authority("sp").appendPath(t.key)
            .appendQueryParameter("q", t.find)
            .appendQueryParameter("t", t.title)
            .appendQueryParameter("a", t.artist)
            .appendQueryParameter("d", t.durationMs.toString())
            .apply { if (t.video.isNotEmpty()) appendQueryParameter("v", t.video) }
            .build()
        Track.Kind.Local -> Uri.parse(t.uri)
    }

    /** The song a "segno://" address stands for (only the fields needed to play it). */
    fun trackFor(uri: Uri): Track? {
        if (uri.scheme != SCHEME) return null
        val key = uri.lastPathSegment ?: return null
        return when (uri.authority) {
            "yt" -> Track(id = Track.YT + key, title = "")
            "sp" -> Track(
                id = Track.SP + key,
                title = uri.getQueryParameter("t").orEmpty(),
                artist = uri.getQueryParameter("a").orEmpty(),
                durationMs = uri.getQueryParameter("d")?.toLongOrNull() ?: 0,
                find = uri.getQueryParameter("q").orEmpty(),
                video = uri.getQueryParameter("v").orEmpty(),
            )
            else -> null
        }
    }

    fun artFor(t: Track, px: Int): String = when {
        t.kind == Track.Kind.Local -> if (t.albumId != 0L) "content://media/external/audio/albumart/${t.albumId}" else ""
        t.art.isNotEmpty() -> Thumbs.sized(t.art, px)
        t.youtubeId != null -> Thumbs.video(t.youtubeId!!, big = px > 360)
        else -> ""
    }

    fun from(t: Track): MediaItem {
        val uri = uriFor(t)
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(metadata(t))
            .build()
    }

    fun metadata(t: Track): MediaMetadata {
        val art = artFor(t, 544)
        return MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist.ifEmpty { null })
            .setAlbumTitle(t.album.ifEmpty { null })
            .setArtworkUri(if (art.isEmpty()) null else Uri.parse(art))
            .setDurationMs(if (t.durationMs > 0) t.durationMs else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(toBundle(t))
            .build()
    }

    /** The song behind a player item. */
    fun toTrack(item: MediaItem): Track {
        val extras = item.mediaMetadata.extras
        if (extras != null && extras.containsKey(K_ID)) return fromBundle(extras)
        val m = item.mediaMetadata
        return Track(
            id = item.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString().orEmpty(),
            durationMs = m.durationMs ?: 0,
            uri = item.localConfiguration?.uri?.toString().orEmpty(),
        )
    }

    /** Makes an item from a controller playable: the address travels in its request metadata. */
    fun playable(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val uri = item.requestMetadata.mediaUri ?: uriFor(toTrack(item))
        return item.buildUpon().setUri(uri).build()
    }

    private const val K_ID = "segno.id"

    fun toBundle(t: Track) = Bundle().apply {
        putString(K_ID, t.id)
        putString("segno.title", t.title)
        putString("segno.artist", t.artist)
        putString("segno.album", t.album)
        putLong("segno.duration", t.durationMs)
        putString("segno.art", t.art)
        putString("segno.find", t.find)
        putString("segno.video", t.video)
        putString("segno.uri", t.uri)
        putLong("segno.albumId", t.albumId)
    }

    fun fromBundle(b: Bundle) = Track(
        id = b.getString(K_ID).orEmpty(),
        title = b.getString("segno.title").orEmpty(),
        artist = b.getString("segno.artist").orEmpty(),
        album = b.getString("segno.album").orEmpty(),
        durationMs = b.getLong("segno.duration"),
        art = b.getString("segno.art").orEmpty(),
        find = b.getString("segno.find").orEmpty(),
        video = b.getString("segno.video").orEmpty(),
        uri = b.getString("segno.uri").orEmpty(),
        albumId = b.getLong("segno.albumId"),
    )
}
