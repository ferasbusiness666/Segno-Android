package io.github.ferasbusiness666.segno.download

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Net
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.youtube.Thumbs
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Puts a downloaded song into Music/Segno with its tags and cover, where every music app on the
 * phone finds it. Android 10 and newer: through MediaStore (no permission needed). Android 8
 * and 9: as a plain file, then the media scanner is told about it.
 */
object SongSaver {
    const val FOLDER = "Segno"

    /** Saves [audio] (an M4A file) for [track]. Returns the content:// (or file://) address. */
    fun save(context: Context, track: Track, audio: File, cover: ByteArray?): String {
        val tags = Mp4Tagger.Tags(
            title = track.title,
            artist = track.artist,
            album = track.album,
            cover = cover,
            comment = "Segno ${track.id}",
        )
        val name = Format.safeFileName(if (track.artist.isNotBlank()) "${track.artist.split(',').first().trim()} - ${track.title}" else track.title) + ".m4a"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(context, track, audio, tags, name) else saveLegacy(context, audio, tags, name)
    }

    private fun saveQ(context: Context, track: Track, audio: File, tags: Mp4Tagger.Tags, name: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, 1)
            put(MediaStore.MediaColumns.TITLE, track.title)
            put(MediaStore.Audio.AudioColumns.ARTIST, track.artist)
            if (track.album.isNotEmpty()) put(MediaStore.Audio.AudioColumns.ALBUM, track.album)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't make the file in Music/$FOLDER")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> Mp4Tagger.write(audio, out, tags) }
                ?: throw IOException("Couldn't write to Music/$FOLDER")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, audio: File, tags: Mp4Tagger.Tags, name: String): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't make the Music/$FOLDER folder")
        var file = File(dir, name)
        var n = 2
        while (file.exists()) {
            file = File(dir, name.removeSuffix(".m4a") + " ($n).m4a")
            n++
        }
        try {
            FileOutputStream(file).use { out -> Mp4Tagger.write(audio, out, tags) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        // Ask the media scanner to add it, and wait (briefly) for its content:// address.
        val latch = CountDownLatch(1)
        var found: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("audio/mp4")) { _, uri ->
            found = uri
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return (found ?: Uri.fromFile(file)).toString()
    }

    /** The cover as a square JPEG, about 600 px, or null when there's none. Blocking. */
    fun cover(track: Track): ByteArray? {
        val url = when {
            track.art.isNotEmpty() -> Thumbs.sized(track.art, 600)
            track.youtubeId != null -> Thumbs.video(track.youtubeId!!, big = true)
            else -> return null
        }
        val bytes = fetch(url) ?: Thumbs.fallback(url)?.let { fetch(it) } ?: return null
        return squareJpeg(bytes)
    }

    private fun fetch(url: String): ByteArray? = try {
        Net.client.newCall(Request.Builder().url(url).header("User-Agent", Net.BROWSER_UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body.bytes() else null
        }
    } catch (e: IOException) {
        null
    }

    /** YouTube pictures are wide; music apps want a square. Crop the middle. */
    private fun squareJpeg(bytes: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val x = (bmp.width - side) / 2
        val y = (bmp.height - side) / 2
        val square = Bitmap.createBitmap(bmp, x, y, side, side)
        val scaled = if (side > 600) Bitmap.createScaledBitmap(square, 600, 600, true) else square
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (scaled !== square) scaled.recycle()
        if (square !== bmp) square.recycle()
        bmp.recycle()
        return out.toByteArray()
    }
}
