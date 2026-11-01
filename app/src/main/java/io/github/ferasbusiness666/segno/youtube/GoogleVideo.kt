package io.github.ferasbusiness666.segno.youtube

import io.github.ferasbusiness666.segno.core.Net
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream

/** YouTube refused an audio address (it expired or was never good). */
class RefusedException(val code: Int) : IOException("YouTube refused the audio address (HTTP $code)")

/**
 * Reads YouTube audio the way YouTube's own apps do: in pieces, each asked for with a "range"
 * and a request number in the address. Long single requests get slowed down or cut off; pieces
 * don't. When YouTube refuses an address, [refresh] gets a new one and reading carries on.
 *
 * Not thread-safe: one reader per stream.
 */
class GoogleVideoReader(
    private var audio: StreamResolver.Audio,
    private val chunkSize: Long = DEFAULT_CHUNK,
    private val refresh: (() -> StreamResolver.Audio)? = null,
) {
    private var position = 0L
    /** Exclusive end, or -1 when the size isn't known. */
    private var end = -1L
    private var body: InputStream? = null
    private var response: Response? = null
    private var call: Call? = null
    private var chunkLeft = 0L
    private var requestNumber = 1
    private var refreshed = false
    /** Size unknown and the last piece came back short: that was the end. */
    private var reachedEnd = false

    val contentLength: Long get() = audio.contentLength
    val mime: String get() = audio.mime

    /** Starts reading at [start]; [length] bytes, or to the end when -1. Returns the bytes to come, or -1. */
    fun open(start: Long, length: Long): Long {
        position = start
        val total = audio.contentLength
        end = when {
            length >= 0 -> start + length
            total > 0 -> total
            else -> -1
        }
        openChunk()
        return if (end >= 0) end - start else -1
    }

    /** Reads up to [len] bytes; -1 at the end. */
    fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (end in 0..position) return -1
        if (body == null || chunkLeft <= 0) {
            if (end < 0 && reachedEnd) return -1
            closeBody()
            openChunk()
        }
        val stream = body ?: return -1
        val want = minOf(len.toLong(), chunkLeft).toInt()
        val n = stream.read(buf, off, want)
        if (n < 0) {
            // The piece ended early. With a known size, ask for the rest; otherwise this is the end.
            closeBody()
            if (end < 0) return -1
            openChunk()
            val again = body?.read(buf, off, minOf(len.toLong(), chunkLeft).toInt()) ?: -1
            if (again < 0) throw IOException("YouTube stopped sending the song early")
            position += again
            chunkLeft -= again
            return again
        }
        position += n
        chunkLeft -= n
        return n
    }

    fun close() {
        call?.cancel()
        closeBody()
    }

    private fun closeBody() {
        try {
            body?.close()
        } catch (_: IOException) {
        }
        response?.close()
        body = null
        response = null
        call = null
        chunkLeft = 0
    }

    private fun openChunk() {
        val last = if (end >= 0) minOf(position + chunkSize, end) - 1 else position + chunkSize - 1
        try {
            request(position, last)
        } catch (e: RefusedException) {
            val r = refresh
            if (r == null || refreshed) throw e
            refreshed = true
            audio = r()
            request(position, last)
        }
        refreshed = false
    }

    private fun request(first: Long, last: Long) {
        val url = audio.url + "&rn=" + requestNumber++ + "&range=" + first + "-" + last
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(audio.url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            Net.BROWSER_UA
        }
        val req = Request.Builder()
            .url(url)
            // YouTube's apps ask for audio with a tiny POST body; plain GETs get refused more often.
            .post(POST_BODY.toRequestBody(null))
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .build()
        val c = Net.client.newCall(req)
        call = c
        val resp = c.execute()
        val code = resp.code
        if (code == 403 || code == 404 || code == 410) {
            resp.close()
            throw RefusedException(code)
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("YouTube answered HTTP $code")
        }
        response = resp
        body = resp.body.byteStream()
        val sent = resp.body.contentLength()
        val asked = last - first + 1
        chunkLeft = if (sent > 0) sent else asked
        if (sent in 0 until asked) reachedEnd = true
    }

    companion object {
        const val DEFAULT_CHUNK = 1L shl 20 // 1 MiB: about a minute of music
        private val POST_BODY = byteArrayOf(0x78, 0)
    }
}
