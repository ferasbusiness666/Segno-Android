package io.github.ferasbusiness666.segno.spotify

import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Tracks
import io.github.ferasbusiness666.segno.youtube.Thumbs
import io.github.ferasbusiness666.segno.youtube.YouTube
import io.github.ferasbusiness666.segno.youtube.YouTubeException
import java.util.concurrent.ConcurrentHashMap

/** Finds the YouTube video that plays a Spotify song, and remembers it. */
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
        val words = track.find.ifEmpty { "${track.artist.split(',').first().trim()} - ${track.title} audio" }
        val results = YouTube.search(words, YouTube.VIDEOS).tracks
        val candidates = results.mapNotNull { t ->
            val id = t.youtubeId ?: return@mapNotNull null
            Matcher.Candidate(id, t.title, t.durationMs / 1000)
        }
        val pick = Matcher.pick(candidates, track.title, track.durationMs)
            ?: throw YouTubeException("Couldn't find this song on YouTube.")
        known[track.id] = pick.videoId
        runCatching {
            Tracks.put(listOf(track))
            Tracks.setVideo(track.id, pick.videoId, Thumbs.video(pick.videoId))
        }
        return pick.videoId
    }

    /** The video already found for a Spotify song in this session, without searching. */
    fun known(trackId: String): String? = known[trackId]
}
