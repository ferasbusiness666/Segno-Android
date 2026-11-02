package io.github.ferasbusiness666.segno.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.ferasbusiness666.segno.core.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Segno's own data: playlists, loved songs, what you played and what you downloaded. A small
 * SQLite database; every call is blocking, so call it off the main thread.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "segno.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks(
                id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '', duration INTEGER NOT NULL DEFAULT 0,
                art TEXT NOT NULL DEFAULT '', find TEXT NOT NULL DEFAULT '', video TEXT NOT NULL DEFAULT '',
                uri TEXT NOT NULL DEFAULT '', album_id INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE playlists(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
                site TEXT NOT NULL DEFAULT '', cover TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL, updated INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE playlist_items(
                playlist_id INTEGER NOT NULL, pos INTEGER NOT NULL, track_id TEXT NOT NULL,
                PRIMARY KEY(playlist_id, pos))""",
        )
        db.execSQL("CREATE INDEX playlist_items_track ON playlist_items(track_id)")
        db.execSQL("CREATE TABLE loved(track_id TEXT PRIMARY KEY, added INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(track_id TEXT PRIMARY KEY, played INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE downloads(
                track_id TEXT PRIMARY KEY, state INTEGER NOT NULL, uri TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '', added INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE related(
                seed TEXT NOT NULL, pos INTEGER NOT NULL, track_id TEXT NOT NULL, PRIMARY KEY(seed, pos))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first one. Future versions add their changes here.
    }

    // Tracks ------------------------------------------------------------------------------------

    /** Saves (or updates) songs, keeping a Spotify song's YouTube match if it already has one. */
    fun putTracks(db: SQLiteDatabase, tracks: Collection<Track>) {
        for (t in tracks) {
            // Never replace something known with a blank (a cover found later, a YouTube match).
            val cv = ContentValues().apply {
                put("title", t.title)
                if (t.artist.isNotEmpty()) put("artist", t.artist)
                if (t.album.isNotEmpty()) put("album", t.album)
                if (t.durationMs > 0) put("duration", t.durationMs)
                if (t.art.isNotEmpty()) put("art", t.art)
                if (t.find.isNotEmpty()) put("find", t.find)
                if (t.video.isNotEmpty()) put("video", t.video)
                if (t.uri.isNotEmpty()) put("uri", t.uri)
                if (t.albumId != 0L) put("album_id", t.albumId)
            }
            val updated = db.update("tracks", cv, "id = ?", arrayOf(t.id))
            if (updated == 0) {
                val all = ContentValues().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("duration", t.durationMs)
                    put("art", t.art)
                    put("find", t.find)
                    put("video", t.video)
                    put("uri", t.uri)
                    put("album_id", t.albumId)
                }
                db.insertWithOnConflict("tracks", null, all, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    fun track(id: String): Track? =
        readableDatabase.rawQuery("SELECT $TRACK_COLS FROM tracks t WHERE t.id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toTrack() else null
        }

    /** A Spotify song's YouTube match, remembered so it isn't searched again. */
    fun setVideo(trackId: String, videoId: String, art: String?) {
        val cv = ContentValues().apply { put("video", videoId) }
        writableDatabase.update("tracks", cv, "id = ?", arrayOf(trackId))
        if (!art.isNullOrEmpty()) {
            writableDatabase.update("tracks", ContentValues().apply { put("art", art) }, "id = ? AND art = ''", arrayOf(trackId))
        }
    }

    companion object {
        private const val VERSION = 1

        const val TRACK_COLS = "t.id, t.title, t.artist, t.album, t.duration, t.art, t.find, t.video, t.uri, t.album_id"

        @Volatile private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context).also { instance = it }
            }

        fun Cursor.toTrack(offset: Int = 0) = Track(
            id = getString(offset),
            title = getString(offset + 1),
            artist = getString(offset + 2),
            album = getString(offset + 3),
            durationMs = getLong(offset + 4),
            art = getString(offset + 5),
            find = getString(offset + 6),
            video = getString(offset + 7),
            uri = getString(offset + 8),
            albumId = getLong(offset + 9),
        )
    }
}

/** Version counters the screens watch to know when to load again. */
object Changes {
    private val _playlists = MutableStateFlow(0L)
    private val _loved = MutableStateFlow(0L)
    private val _history = MutableStateFlow(0L)
    private val _downloads = MutableStateFlow(0L)
    private val _local = MutableStateFlow(0L)

    val playlists: StateFlow<Long> = _playlists
    val loved: StateFlow<Long> = _loved
    val history: StateFlow<Long> = _history
    val downloads: StateFlow<Long> = _downloads

    /** Music files on the phone (one was deleted). */
    val local: StateFlow<Long> = _local

    fun playlists() { _playlists.value++ }
    fun loved() { _loved.value++ }
    fun history() { _history.value++ }
    fun downloads() { _downloads.value++ }
    fun local() { _local.value++ }
}

inline fun <T> SQLiteDatabase.tx(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val r = block()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}
