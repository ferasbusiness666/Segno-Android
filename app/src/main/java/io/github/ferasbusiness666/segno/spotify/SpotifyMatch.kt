package io.github.ferasbusiness666.segno.spotify

import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Tracks
import io.github.ferasbusiness666.segno.lyrics.Names
import io.github.ferasbusiness666.segno.lyrics.YtMusic
import io.github.ferasbusiness666.segno.youtube.Thumbs
import io.github.ferasbusiness666.segno.youtube.YouTube
import io.github.ferasbusiness666.segno.youtube.YouTubeException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Finds the YouTube video that plays a Spotify song (YouTube Music's song first), and remembers it. */
object SpotifyMatch {
    private val known = ConcurrentHashMap<String, String>()

    /** The YouTube video id for [track]. Blocking; throws [YouTubeException]. */
    fun videoFor(track: Track): String {
        if (track.video.isNotEmpty()) return track.video
        known[track.id]?.let { return it }
        Tracks.get(track.id)?.video?.takeIf { it.isNotEmpty() }?.let {
            known[track.id] = it
            return it
        }
        // The same song on YouTube Music first: the official album audio, so the same length and
        // lyrics timed to it. Then a YouTube search as before.
        val pick = musicMatch(track) ?: searchYouTube(track)
        known[track.id] = pick
        runCatching {
            Tracks.put(listOf(track))
            Tracks.setVideo(track.id, pick, Thumbs.video(pick))
        }
        return pick
    }

    /** A Spotify song's twin on YouTube Music (desktop's music_match). Null: no sure match, or no answer. */
    private fun musicMatch(track: Track): String? {
        val main = track.artist.split(',').first().trim()
        val songs = runCatching { YtMusic.searchSongs("${track.title} $main".trim()) }.getOrNull() ?: return null
        return pickMatch(track.title, track.artist, track.durationMs / 1000f, songs)
    }

    /**
     * Same name and artist, the same version (not live or sped up unless it is), and within 3 s
     * of its length (desktop's pick_match).
     */
    fun pickMatch(title: String, artist: String, durationSec: Float, songs: List<YtMusic.Song>): String? {
        val ours = Names.versionKinds(title)
        return songs.firstOrNull { s ->
            Names.sameSong(s.title, title) && Names.sameArtist(s.artist, artist) &&
                (durationSec <= 0f || abs(s.duration - durationSec) <= 3f) && Names.versionKinds(s.title) == ours
        }?.id
    }

    private fun searchYouTube(track: Track): String {
        val words = track.find.ifEmpty { "${track.artist.split(',').first().trim()} - ${track.title} audio" }
        val results = YouTube.search(words, YouTube.VIDEOS).tracks
        val candidates = results.mapNotNull { t ->
            val id = t.youtubeId ?: return@mapNotNull null
            Matcher.Candidate(id, t.title, t.durationMs / 1000)
        }
        val pick = Matcher.pick(candidates, track.title, track.durationMs)
            ?: throw YouTubeException("Couldn't find this song on YouTube.")
        return pick.videoId
    }

    /** The video already found for a Spotify song in this session, without searching. */
    fun known(trackId: String): String? = known[trackId]
}
