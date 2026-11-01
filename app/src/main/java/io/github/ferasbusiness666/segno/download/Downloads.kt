package io.github.ferasbusiness666.segno.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.ferasbusiness666.segno.SegnoApp
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Changes
import io.github.ferasbusiness666.segno.data.Db
import io.github.ferasbusiness666.segno.data.Db.Companion.TRACK_COLS
import io.github.ferasbusiness666.segno.data.Db.Companion.toTrack
import io.github.ferasbusiness666.segno.data.tx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Where a song's download is at. */
data class DownloadState(
    val trackId: String,
    val state: Int,
    /** 0..1 while downloading. */
    val progress: Float = 0f,
    val uri: String = "",
    val error: String = "",
) {
    val isDone get() = state == DONE
    val isActive get() = state == WAITING || state == RUNNING

    companion object {
        const val WAITING = 0
        const val RUNNING = 1
        const val DONE = 2
        const val FAILED = 3
    }
}

/**
 * Downloads: songs saved to Music/Segno on the phone. Keeps every download's state in memory for
 * the screens (and the player, which plays a downloaded copy instead of streaming).
 */
object Downloads {
    private val db get() = Db.get(SegnoApp.instance)
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    @Volatile private var loaded = false

    /** Reads the list once. Blocking. */
    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val map = HashMap<String, DownloadState>()
            db.readableDatabase.rawQuery("SELECT track_id, state, uri, error FROM downloads", null).use { c ->
                while (c.moveToNext()) {
                    var state = c.getInt(1)
                    // Anything cut off when the app last closed waits again.
                    if (state == DownloadState.RUNNING) state = DownloadState.WAITING
                    map[c.getString(0)] = DownloadState(c.getString(0), state, 0f, c.getString(2), c.getString(3))
                }
            }
            _states.value = map
            loaded = true
        }
    }

    fun state(trackId: String): DownloadState? = _states.value[trackId]

    /** The downloaded file for a song, if there is one. */
    fun localUri(trackId: String): Uri? {
        load()
        val s = _states.value[trackId] ?: return null
        return if (s.isDone && s.uri.isNotEmpty()) Uri.parse(s.uri) else null
    }

    fun doneTracks(): List<Track> =
        db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM downloads d JOIN tracks t ON t.id = d.track_id ORDER BY d.done DESC, d.added DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }

    /** Every song with a download, finished or not, newest first. */
    fun allTracks(): List<Track> = doneTracks()

    /** Queues songs to download (YouTube and Spotify songs; files on the phone are skipped). Blocking. */
    fun enqueue(context: Context, tracks: List<Track>): Int {
        load()
        val fresh = tracks.filter { it.isOnline }.filter { val s = state(it.id); s == null || s.state == DownloadState.FAILED }
            .distinctBy { it.id }
        if (fresh.isEmpty()) return 0
        val now = System.currentTimeMillis()
        db.writableDatabase.tx {
            db.putTracks(this, fresh)
            fresh.forEachIndexed { i, t ->
                insertWithOnConflict(
                    "downloads",
                    null,
                    ContentValues().apply {
                        put("track_id", t.id)
                        put("state", DownloadState.WAITING)
                        put("added", now + i)
                        put("uri", "")
                        put("error", "")
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        update { m -> fresh.forEach { m[it.id] = DownloadState(it.id, DownloadState.WAITING) } }
        start(context)
        return fresh.size
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    /** The next song waiting, oldest first. Blocking. */
    fun nextWaiting(): Track? =
        db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM downloads d JOIN tracks t ON t.id = d.track_id WHERE d.state IN (0, 1) ORDER BY d.added LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) c.toTrack() else null }

    fun waitingCount(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM downloads WHERE state IN (0, 1)", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun setRunning(id: String, progress: Float) {
        update { m -> m[id] = DownloadState(id, DownloadState.RUNNING, progress) }
    }

    fun setDone(id: String, uri: String) {
        db.writableDatabase.update(
            "downloads",
            ContentValues().apply {
                put("state", DownloadState.DONE)
                put("uri", uri)
                put("error", "")
                put("done", System.currentTimeMillis())
            },
            "track_id = ?",
            arrayOf(id),
        )
        update { m -> m[id] = DownloadState(id, DownloadState.DONE, 1f, uri) }
        Changes.downloads()
    }

    fun setFailed(id: String, error: String) {
        db.writableDatabase.update(
            "downloads",
            ContentValues().apply {
                put("state", DownloadState.FAILED)
                put("error", error)
            },
            "track_id = ?",
            arrayOf(id),
        )
        update { m -> m[id] = DownloadState(id, DownloadState.FAILED, 0f, "", error) }
        Changes.downloads()
    }

    /** Stops songs still waiting. The one downloading now is stopped by the service. Blocking. */
    fun cancelWaiting() {
        val ids = _states.value.values.filter { it.isActive }.map { it.trackId }
        db.writableDatabase.delete("downloads", "state IN (0, 1)", null)
        update { m -> ids.forEach { m.remove(it) } }
        Changes.downloads()
    }

    /** Deletes a downloaded song from the phone. Blocking. */
    fun delete(context: Context, trackId: String) {
        val s = state(trackId)
        if (s != null && s.uri.isNotEmpty()) {
            runCatching {
                val uri = Uri.parse(s.uri)
                if (uri.scheme == "file") {
                    File(uri.path.orEmpty()).delete()
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        // Before Android 10 the file is ours to delete directly.
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0)?.let { File(it).delete() }
                        }
                    }
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
        db.writableDatabase.delete("downloads", "track_id = ?", arrayOf(trackId))
        update { m -> m.remove(trackId) }
        Changes.downloads()
    }

    /** A downloaded file that's gone (deleted in another app): forget it. Blocking. */
    fun forgetMissing(trackId: String) {
        db.writableDatabase.delete("downloads", "track_id = ?", arrayOf(trackId))
        update { m -> m.remove(trackId) }
        Changes.downloads()
    }

    private inline fun update(f: (MutableMap<String, DownloadState>) -> Unit) {
        synchronized(this) {
            val m = HashMap(_states.value)
            f(m)
            _states.value = m
        }
    }
}
