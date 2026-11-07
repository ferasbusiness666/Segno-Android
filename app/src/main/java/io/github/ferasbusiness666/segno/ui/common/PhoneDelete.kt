package io.github.ferasbusiness666.segno.ui.common

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Changes
import io.github.ferasbusiness666.segno.data.Forget
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Delete from phone": removes a music file (a song on the phone, or a downloaded copy).
 *
 * Android 11+ asks with its own dialog (MediaStore.createDeleteRequest), and that is the only
 * question. Android 10 deletes Segno's own files directly and asks Android for the others. Android
 * 8 and 9 delete the file with the storage permission, after Segno's own short question.
 */
object PhoneDelete {
    private const val TAG = "Segno"

    /** Songs with a file on the phone: local songs and finished downloads. */
    fun canDelete(track: Track): Boolean =
        (track.kind == Track.Kind.Local && !track.isMissing) || Downloads.state(track.id)?.isDone == true

    fun start(main: MainActivity, track: Track) {
        main.lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) { target(main, track) }
            if (target == null) {
                // The file is already gone: tidy up what pointed at it.
                withContext(Dispatchers.IO) { forget(track, null) }
                removeFromQueue(track, null)
                Messages.show(main.getString(R.string.toast_deleted, track.title))
                return@launch
            }
            when {
                Build.VERSION.SDK_INT >= 30 && target.scheme == "content" -> {
                    val request = MediaStore.createDeleteRequest(main.contentResolver, listOf(target))
                    main.askSystem(request.intentSender) { ok -> if (ok) done(main, track, target) }
                }
                Build.VERSION.SDK_INT == 29 && target.scheme == "content" -> confirm(main, track) { deleteQ(main, track, target) }
                else -> {
                    val write = Manifest.permission.WRITE_EXTERNAL_STORAGE
                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(main, write) != PackageManager.PERMISSION_GRANTED) {
                        main.askStorage { start(main, track) }
                    } else {
                        confirm(main, track) { deleteLegacy(main, track, target) }
                    }
                }
            }
        }
    }

    private fun confirm(main: MainActivity, track: Track, then: () -> Unit) {
        MaterialAlertDialogBuilder(main)
            .setTitle(main.getString(R.string.delete_song_title, track.title))
            .setMessage(R.string.delete_song_body)
            .setPositiveButton(R.string.delete_song_ok) { _, _ -> then() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Android 10: Segno's own files go at once; for others Android asks the user first. */
    private fun deleteQ(main: MainActivity, track: Track, uri: Uri) {
        main.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { main.contentResolver.delete(uri, null, null) }
                done(main, track, uri)
            } catch (e: SecurityException) {
                val recoverable = if (Build.VERSION.SDK_INT >= 29) e as? RecoverableSecurityException else null
                if (recoverable == null) {
                    failed(main, track, e)
                    return@launch
                }
                main.askSystem(recoverable.userAction.actionIntent.intentSender) { ok -> if (ok) deleteQ(main, track, uri) }
            }
        }
    }

    /** Android 8 and 9: the file itself, then its row in the media library. */
    private fun deleteLegacy(main: MainActivity, track: Track, uri: Uri) {
        main.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val path = if (uri.scheme == "file") uri.path else dataPath(main, uri)
                    val file = path?.let(::File)
                    val gone = file == null || !file.exists() || file.delete()
                    if (gone && uri.scheme == "content") main.contentResolver.delete(uri, null, null)
                    gone
                }.onFailure { Log.w(TAG, "Couldn't delete $uri", it) }.getOrDefault(false)
            }
            if (ok) done(main, track, uri) else failed(main, track, null)
        }
    }

    private fun done(main: MainActivity, track: Track, uri: Uri) {
        main.lifecycleScope.launch {
            withContext(Dispatchers.IO) { forget(track, uri) }
            removeFromQueue(track, uri)
            Messages.show(main.getString(R.string.toast_deleted, track.title))
        }
    }

    private fun failed(main: MainActivity, track: Track, e: Throwable?) {
        if (e != null) Log.w(TAG, "Couldn't delete ${track.id}", e)
        Messages.show(main.getString(R.string.toast_delete_failed, track.title))
    }

    /** The song's file as a MediaStore address (or a file:// one from an old download). Blocking. */
    private fun target(context: Context, track: Track): Uri? {
        val uri = when (track.kind) {
            Track.Kind.Local -> track.uri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
                ?: track.key.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
            else -> Downloads.localUri(track.id)
        } ?: return null
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            if (!File(path).exists()) return null
            return mediaUriFor(context, path) ?: uri
        }
        val exists = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
        }.getOrNull()
        return if (exists == false) null else uri
    }

    @Suppress("DEPRECATION")
    private fun dataPath(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    @Suppress("DEPRECATION")
    private fun mediaUriFor(context: Context, path: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }.getOrNull()

    /** The file is gone: drop it from playlists, Loved songs, history and the downloads list. Blocking. */
    private fun forget(track: Track, uri: Uri?) {
        val mediaId = localId(track, uri)
        if (track.kind == Track.Kind.Local) {
            Forget.everywhere(track.id)
        } else {
            // A downloaded YouTube or Spotify song still plays from the internet, so it stays put.
            Downloads.forgetMissing(track.id)
        }
        if (mediaId != null) {
            if (track.kind != Track.Kind.Local) Forget.everywhere(Track.LOCAL + mediaId)
            Downloads.forgetFile(mediaId)
        }
        Changes.local()
    }

    private fun localId(track: Track, uri: Uri?): Long? =
        if (track.kind == Track.Kind.Local) track.key.toLongOrNull()
        else uri?.takeIf { it.scheme == "content" }?.lastPathSegment?.toLongOrNull()

    /** Takes the deleted file out of the queue (main thread, like every player call). */
    private fun removeFromQueue(track: Track, uri: Uri?) {
        if (track.kind == Track.Kind.Local) PlayerHub.removeTrack(track.id)
        if (track.kind != Track.Kind.Local) localId(track, uri)?.let { PlayerHub.removeTrack(Track.LOCAL + it) }
    }
}
