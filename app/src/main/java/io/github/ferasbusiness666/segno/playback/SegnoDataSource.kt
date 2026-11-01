@file:OptIn(UnstableApi::class)

package io.github.ferasbusiness666.segno.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.spotify.SpotifyMatch
import io.github.ferasbusiness666.segno.youtube.GoogleVideoReader
import io.github.ferasbusiness666.segno.youtube.StreamResolver
import java.io.IOException

/**
 * Plays "segno://" addresses: a downloaded copy if there is one, otherwise YouTube's audio,
 * looked up right when the song is about to play. Spotify songs are matched to a YouTube video
 * first. Everything else (files on the phone) goes to ExoPlayer's own data source.
 */
class SegnoDataSource(private val context: Context) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var reader: GoogleVideoReader? = null
    private var local: DataSource? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var headers: Map<String, List<String>> = emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val track = MediaItems.trackFor(dataSpec.uri) ?: throw IOException("Not a Segno address: ${dataSpec.uri}")

        // A downloaded copy plays from the phone, even offline.
        Downloads.localUri(track.id)?.let { file ->
            val ds = DefaultDataSource.Factory(context).createDataSource()
            try {
                val len = ds.open(dataSpec.buildUpon().setUri(file).build())
                local = ds
                opened = true
                transferStarted(dataSpec)
                return len
            } catch (e: IOException) {
                runCatching { ds.close() }
                Downloads.forgetMissing(track.id)
            }
        }

        val videoId = videoFor(track)
        val audio = StreamResolver.resolve(videoId)
        val r = GoogleVideoReader(audio) {
            StreamResolver.invalidate(videoId)
            StreamResolver.resolve(videoId)
        }
        val len = r.open(dataSpec.position, dataSpec.length)
        reader = r
        headers = mapOf("Content-Type" to listOf(audio.mime))
        remaining = if (len >= 0) len else C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun videoFor(track: Track): String {
        track.youtubeId?.let { return it }
        val id = SpotifyMatch.videoFor(track)
        onSpotifyMatched?.invoke(track.id, id)
        return id
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        local?.let { ds ->
            val n = ds.read(buffer, offset, length)
            if (n > 0) bytesTransferred(n)
            return n
        }
        val r = reader ?: return C.RESULT_END_OF_INPUT
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = r.read(buffer, offset, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        uri = null
        try {
            reader?.close()
            local?.close()
        } finally {
            reader = null
            local = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Hands out [SegnoDataSource]s for "segno://" and ExoPlayer's default one for the rest. */
    class Factory(context: Context) : DataSource.Factory {
        private val app = context.applicationContext
        private val fallback = DefaultDataSource.Factory(app)

        override fun createDataSource(): DataSource = Routing(SegnoDataSource(app), fallback.createDataSource())
    }

    /** Picks the right data source when a song is opened. */
    private class Routing(private val segno: DataSource, private val other: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            segno.addTransferListener(transferListener)
            other.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val ds = if (dataSpec.uri.scheme == MediaItems.SCHEME) segno else other
            current = ds
            return ds.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }

    companion object {
        /** Told when a Spotify song gets its YouTube video, so its cover can be shown. */
        @Volatile var onSpotifyMatched: ((trackId: String, videoId: String) -> Unit)? = null
    }
}
