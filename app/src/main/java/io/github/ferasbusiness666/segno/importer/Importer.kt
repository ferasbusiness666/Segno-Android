package io.github.ferasbusiness666.segno.importer

import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.spotify.RemotePlaylist
import io.github.ferasbusiness666.segno.spotify.Spotify
import io.github.ferasbusiness666.segno.spotify.SpotifyParser
import io.github.ferasbusiness666.segno.youtube.Links
import io.github.ferasbusiness666.segno.youtube.YouTube
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Importing playlists from links, like desktop's Import page: paste playlist links, a YouTube
 * channel (all its public playlists), or songs copied out of Spotify.
 */
object Importer {
    /** One thing to look up. */
    sealed interface Source {
        /** A playlist or album link. */
        data class Link(val url: String) : Source
        /** A YouTube channel: its public playlists. */
        data class Channel(val url: String) : Source
        /** A Spotify profile: only to explain that its playlists can't be read. */
        data class Profile(val url: String) : Source
        /** Songs copied out of Spotify (Ctrl+A, Ctrl+C copies one link per song). */
        data class Songs(val ids: List<String>) : Source
    }

    /** A playlist found, waiting to be ticked. [songs] is null until read (a channel's playlists). */
    data class Found(val info: RemotePlaylist, val songs: List<Track>?, val picked: Boolean = true)

    /** Everything worth looking up in pasted text, in order, once each. */
    fun sources(text: String): List<Source> {
        val out = ArrayList<Source>()
        for (raw in text.split(Regex("\\s+"))) {
            val tok = raw.trim(',', ';', '"', '\'', '<', '>', '(', ')', '[', ']')
            if (!Links.looksLikeLink(tok)) continue
            val url = Links.fullUrl(tok)
            if (SpotifyParser.trackIdsIn(url).isNotEmpty()) continue // songs: gathered below
            val src = spotifyProfile(url)?.let { Source.Profile(it) }
                ?: Links.channelPlaylists(url)?.let { Source.Channel(it) }
                ?: Source.Link(url)
            if (src !in out) out += src
        }
        val songs = SpotifyParser.trackIdsIn(text)
        if (songs.isNotEmpty()) out += Source.Songs(songs)
        return out
    }

    /** "open.spotify.com/user/ID" (or "spotify:user:ID") → the profile page. */
    fun spotifyProfile(url: String): String? {
        var rest = when {
            url.contains("spotify.com/") -> url.substringAfter("spotify.com/")
            url.startsWith("spotify:") -> url.removePrefix("spotify:")
            else -> return null
        }
        if (rest.startsWith("intl-")) rest = rest.substringAfter('/', "")
        val id = when {
            rest.startsWith("user/") -> rest.removePrefix("user/")
            rest.startsWith("user:") -> rest.removePrefix("user:")
            else -> return null
        }.takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }
        return if (id.isEmpty()) null else "https://open.spotify.com/user/$id"
    }

    /** A link cut short for a message. */
    fun short(url: String): String {
        val u = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return if (u.length > 48) u.take(47) + "…" else u
    }

    /** What a link holds: its songs and its name. Throws with a message to show. */
    fun read(url: String): Pair<List<Track>, RemotePlaylist?> = when {
        SpotifyParser.isSpotify(url) -> Spotify.resolve(url)
        Links.playlistId(url) != null -> {
            val (info, tracks) = YouTube.playlist(Links.playlistUrl(Links.playlistId(url)!!))
            tracks to info.copy(url = url)
        }
        Links.videoId(url) != null -> listOf(YouTube.video(Links.videoId(url)!!)) to null
        else -> throw UnsupportedLink()
    }

    class UnsupportedLink : Exception("unsupported")

    /** Reads songs copied out of Spotify, four at a time. Returns them in order, and how many failed. */
    fun readSpotifySongs(ids: List<String>, progress: (Int) -> Unit): Pair<List<Track>, Int> {
        val pool = Executors.newFixedThreadPool(minOf(4, ids.size.coerceAtLeast(1)))
        val done = AtomicInteger(0)
        try {
            val futures = ids.map { id ->
                pool.submit(
                    Callable {
                        val t = Spotify.track(id)
                        progress(done.incrementAndGet())
                        t
                    },
                )
            }
            val tracks = futures.mapNotNull { runCatching { it.get() }.getOrNull() }
            return tracks to (ids.size - tracks.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
