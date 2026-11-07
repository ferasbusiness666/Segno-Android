package io.github.ferasbusiness666.segno.lyrics

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.data.Tracks
import io.github.ferasbusiness666.segno.spotify.SpotifyMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Lyrics for Now Playing. Nothing is looked up until the lyrics are opened (and then only the
 * song playing, plus the next one once it's found), so weak phones and data plans aren't spent
 * on lyrics nobody reads. The Synced lyrics setting turns it all off.
 */
object LyricsCenter {
    /** What's shown: the song's id, its lyrics state, and how far Earlier/Later moved them. */
    data class Shown(val trackId: String, val state: LyricsState, val nudged: Float = 0f)

    private val _shown = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = _shown

    @Volatile private var store: Store? = null
    @Volatile private var worker: Worker? = null
    /** The query of the song playing, for Earlier/Later and "Wrong lyrics?". */
    @Volatile private var lastQuery: Query? = null
    @Volatile private var aheadFor: String? = null

    private fun start(context: Context): Pair<Store, Worker> {
        synchronized(this) {
            val s = store ?: Store(File(context.applicationContext.filesDir, "lyrics")).also { store = it }
            val w = worker ?: Worker(look = s::lookup, reply = ::onReply, before = s::tidy).also { worker = it }
            return s to w
        }
    }

    private fun onReply(id: String, state: LyricsState) {
        val cur = _shown.value
        if (cur?.trackId == id) _shown.value = cur.copy(state = state, nudged = if (state is LyricsState.Found) cur.nudged else 0f)
    }

    /**
     * Look up the song playing. [force]: even if it wasn't found lately ("Try again"); [avoid]:
     * "Wrong lyrics?". Blocking (reads the phone's media list), so call it off the main thread.
     */
    fun request(context: Context, track: Track, durationMs: Long, force: Boolean = false, avoid: Long? = null) {
        if (!Settings.syncedLyrics) return
        val same = _shown.value?.trackId == track.id
        if (same && !force && avoid == null && _shown.value?.state !is LyricsState.Failed) return
        if (track.isMissing) {
            _shown.value = Shown(track.id, LyricsState.Missing)
            return
        }
        val q = query(context, track, durationMs)?.copy(force = force, avoid = avoid) ?: return
        lastQuery = q
        if (!same) aheadFor = null
        _shown.value = Shown(track.id, LyricsState.Loading, if (same && avoid == null) _shown.value?.nudged ?: 0f else 0f)
        start(context).second.send(q)
    }

    /** Read the next song's lyrics ahead, quietly, once the one playing is done. Blocking. */
    fun ahead(context: Context, next: Track, durationMs: Long) {
        if (!Settings.syncedLyrics || next.isMissing || aheadFor == next.id) return
        aheadFor = next.id
        val q = query(context, next, durationMs)?.copy(ahead = true) ?: return
        start(context).second.send(q)
    }

    /** "Earlier" / "Later": move the lyrics of the song playing by [by] seconds, and keep that. Blocking. */
    fun nudge(context: Context, by: Float) {
        val cur = _shown.value ?: return
        val l = (cur.state as? LyricsState.Found)?.lyrics?.takeIf { it.synced } ?: return
        val q = lastQuery?.takeIf { it.id == cur.trackId } ?: return
        val moved = l.shifted(by)
        start(context).first.keepTiming(q, moved)
        _shown.value = cur.copy(state = LyricsState.Found(moved), nudged = cur.nudged + by)
    }

    /** Lookups stop (the setting was turned off). */
    fun clear() {
        _shown.value = null
        lastQuery = null
    }

    /** What to ask about a song. Null when there's nothing to ask (a song from a backup that isn't here). */
    fun query(context: Context, t: Track, durationMs: Long): Query? {
        val ms = if (t.durationMs > 0) t.durationMs else durationMs
        val dur = ms / 1000f
        return when (t.kind) {
            Track.Kind.YouTube -> Query(id = t.id, title = t.title, artist = t.artist, fromVideo = true, video = t.key, duration = dur)
            Track.Kind.Spotify -> {
                val video = t.video.ifEmpty { SpotifyMatch.known(t.id) ?: runCatching { Tracks.get(t.id)?.video }.getOrNull().orEmpty() }
                Query(id = t.id, title = t.title, artist = t.artist, video = video.takeIf { it.length == 11 }, duration = dur)
            }
            Track.Kind.Local -> {
                if (t.isMissing) return null
                Query(id = t.id, songPath = pathOf(context, t), title = t.title, artist = t.artist, duration = dur)
            }
        }
    }

    /** Where a phone song's file is, for a `.lrc` beside it. */
    @Suppress("DEPRECATION")
    private fun pathOf(context: Context, t: Track): String? {
        val id = t.key.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
