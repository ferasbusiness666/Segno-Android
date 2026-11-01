package io.github.ferasbusiness666.segno.youtube

import android.net.Uri
import android.os.SystemClock
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.youtube.YouTube.toTrack
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finds the audio address of a YouTube video. Addresses stop working after a few hours, so they're
 * looked up when a song is about to play (not when it's queued), kept for a while, and looked up
 * again when YouTube refuses one.
 */
object StreamResolver {
    data class Audio(
        val videoId: String,
        val url: String,
        val mime: String,
        /** Size in bytes, or -1 when unknown. */
        val contentLength: Long,
        /** elapsedRealtime when the address stops working. */
        val expiresAt: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val art: String,
        /** What YouTube suggests after this one. */
        val related: List<Track>,
    ) {
        val isM4a get() = mime == "audio/mp4"
    }

    private const val KEEP = 40
    private val cache = object : LinkedHashMap<String, Audio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Audio>?) = size > KEEP
    }
    private val locks = ConcurrentHashMap<String, Any>()
    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "segno-prefetch").apply { isDaemon = true } }

    /** A still-good address, without asking YouTube. */
    fun cached(videoId: String): Audio? = synchronized(cache) {
        cache[videoId]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() + 60_000 }
    }

    /** The audio of [videoId], from memory or from YouTube. Blocking; throws [YouTubeException]. */
    fun resolve(videoId: String, preferM4a: Boolean = true): Audio {
        cached(videoId)?.let { if (!preferM4a || it.isM4a) return it }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            cached(videoId)?.let { if (!preferM4a || it.isM4a) return it }
            val audio = try {
                YouTube.guard { fetch(videoId) }
            } catch (e: YouTubeException) {
                // The extractor's own words, for logcat and bug reports.
                android.util.Log.w("Segno", "Couldn't get the audio of $videoId: ${e.message} (${e.cause})")
                throw e
            }
            synchronized(cache) { cache[videoId] = audio }
            return audio
        }
    }

    /** Forget a refused address, so the next [resolve] asks YouTube again. */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    /** Look up [videoId] in the background, so it's ready when it plays. */
    fun prefetch(videoId: String) {
        if (cached(videoId) != null) return
        background.execute { runCatching { resolve(videoId) } }
    }

    private fun fetch(videoId: String): Audio {
        val info = StreamInfo.getInfo(YouTube.service, "https://www.youtube.com/watch?v=$videoId")
        val stream = pick(info.audioStreams)
            ?: throw YouTubeException("YouTube didn't give Segno the sound for this one.")
        val url = stream.content
        val uri = Uri.parse(url)
        val expireSecs = uri.getQueryParameter("expire")?.toLongOrNull()
        val lifetimeMs = if (expireSecs != null) {
            (expireSecs * 1000 - System.currentTimeMillis()).coerceIn(10 * 60_000L, 6 * 3_600_000L)
        } else {
            4 * 3_600_000L
        }
        // Treat it as expired half an hour early, so a long pause doesn't end on a refused address.
        val expiresAt = SystemClock.elapsedRealtime() + lifetimeMs - 30 * 60_000L
        val length = stream.itagItem?.contentLength?.takeIf { it > 0 }
            ?: uri.getQueryParameter("clen")?.toLongOrNull()
            ?: -1L
        val related = info.relatedItems.mapNotNull { it.toTrack() }
        return Audio(
            videoId = videoId,
            url = url,
            mime = if (stream.format == MediaFormat.M4A) "audio/mp4" else "audio/webm",
            contentLength = length,
            expiresAt = expiresAt,
            title = info.name.orEmpty(),
            artist = Format.cleanArtist(info.uploaderName),
            durationMs = info.duration.coerceAtLeast(0) * 1000,
            art = YouTube.bestImage(info.thumbnails, 720).ifEmpty { Thumbs.video(videoId, big = true) },
            related = related,
        )
    }

    /** M4A (AAC) first: every phone decodes it in hardware and it's what downloads are saved as. */
    private fun pick(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }
        val m4a = usable.filter { it.format == MediaFormat.M4A }
        // Prefer the common 128 kbps one (itag 140) over bigger ones some videos list.
        m4a.firstOrNull { it.itag == 140 }?.let { return it }
        m4a.maxByOrNull { it.averageBitrate }?.let { return it }
        return usable.filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA }
            .maxByOrNull { it.averageBitrate }
    }
}
