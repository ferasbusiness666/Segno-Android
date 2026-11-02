package io.github.ferasbusiness666.segno.data

import android.content.ContentValues
import io.github.ferasbusiness666.segno.SegnoApp
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Db.Companion.TRACK_COLS
import io.github.ferasbusiness666.segno.data.Db.Companion.toTrack

private val db: Db get() = Db.get(SegnoApp.instance)

data class Playlist(
    val id: Long,
    val name: String,
    /** The link it was imported from, or empty. */
    val source: String,
    val site: String,
    val cover: String,
    val count: Int,
    val durationMs: Long,
    /** The first song's cover, for playlists without their own picture. */
    val firstArt: String,
    val firstTrack: Track?,
)

/** Playlists you made or imported. All calls are blocking. */
object Playlists {
    fun all(): List<Playlist> {
        val out = mutableListOf<Playlist>()
        db.readableDatabase.rawQuery(
            """SELECT p.id, p.name, p.source, p.site, p.cover,
                 (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id = p.id),
                 (SELECT COALESCE(SUM(t.duration), 0) FROM playlist_items i JOIN tracks t ON t.id = i.track_id WHERE i.playlist_id = p.id)
               FROM playlists p ORDER BY p.updated DESC""",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val first = firstTrack(id)
                out += Playlist(id, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getLong(6), first?.art.orEmpty(), first)
            }
        }
        return out
    }

    private fun firstTrack(id: Long): Track? =
        db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM playlist_items i JOIN tracks t ON t.id = i.track_id WHERE i.playlist_id = ? ORDER BY i.pos LIMIT 1",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.toTrack() else null }

    fun get(id: Long): Playlist? = all().firstOrNull { it.id == id }

    fun tracks(id: Long): List<Track> =
        db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM playlist_items i JOIN tracks t ON t.id = i.track_id WHERE i.playlist_id = ? ORDER BY i.pos",
            arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }

    fun bySource(source: String): Long? =
        db.readableDatabase.rawQuery("SELECT id FROM playlists WHERE source = ? AND source != ''", arrayOf(source)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    fun create(name: String, tracks: List<Track> = emptyList(), source: String = "", site: String = "", cover: String = ""): Long {
        val now = System.currentTimeMillis()
        val id = db.writableDatabase.tx {
            val id = insert(
                "playlists",
                null,
                ContentValues().apply {
                    put("name", name)
                    put("source", source)
                    put("site", site)
                    put("cover", cover)
                    put("created", now)
                    put("updated", now)
                },
            )
            db.putTracks(this, tracks)
            tracks.forEachIndexed { i, t -> insertItem(this, id, i, t.id) }
            id
        }
        Changes.playlists()
        return id
    }

    /** Adds songs at the end, skipping ones already there. Returns how many were added. */
    fun add(id: Long, tracks: List<Track>): Int {
        var added = 0
        db.writableDatabase.tx {
            val have = rawQuery("SELECT track_id FROM playlist_items WHERE playlist_id = ?", arrayOf(id.toString())).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            var pos = rawQuery("SELECT COALESCE(MAX(pos) + 1, 0) FROM playlist_items WHERE playlist_id = ?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            val fresh = tracks.filter { it.id !in have }.distinctBy { it.id }
            db.putTracks(this, fresh)
            for (t in fresh) insertItem(this, id, pos++, t.id)
            added = fresh.size
            touch(this, id)
        }
        Changes.playlists()
        return added
    }

    /** Puts the playlist's songs in this order (after a drag or a removal). */
    fun setOrder(id: Long, trackIds: List<String>) {
        db.writableDatabase.tx {
            delete("playlist_items", "playlist_id = ?", arrayOf(id.toString()))
            trackIds.forEachIndexed { i, t -> insertItem(this, id, i, t) }
            touch(this, id)
        }
        Changes.playlists()
    }

    fun remove(id: Long, trackId: String) {
        val ids = tracks(id).map { it.id }.filter { it != trackId }
        setOrder(id, ids)
    }

    fun rename(id: Long, name: String) {
        db.writableDatabase.update("playlists", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
        Changes.playlists()
    }

    fun delete(id: Long) {
        db.writableDatabase.tx {
            delete("playlist_items", "playlist_id = ?", arrayOf(id.toString()))
            delete("playlists", "id = ?", arrayOf(id.toString()))
        }
        Changes.playlists()
    }

    private fun insertItem(d: android.database.sqlite.SQLiteDatabase, id: Long, pos: Int, trackId: String) {
        d.insert(
            "playlist_items",
            null,
            ContentValues().apply {
                put("playlist_id", id)
                put("pos", pos)
                put("track_id", trackId)
            },
        )
    }

    private fun touch(d: android.database.sqlite.SQLiteDatabase, id: Long) {
        d.update("playlists", ContentValues().apply { put("updated", System.currentTimeMillis()) }, "id = ?", arrayOf(id.toString()))
    }
}

/** Loved songs. */
object Loved {
    @Volatile private var cache: Set<String>? = null

    fun ids(): Set<String> =
        cache ?: db.readableDatabase.rawQuery("SELECT track_id FROM loved", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }.also { cache = it }

    fun isLoved(id: String) = id in ids()

    internal fun dropCache() {
        cache = null
    }

    fun tracks(): List<Track> =
        db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM loved l JOIN tracks t ON t.id = l.track_id ORDER BY l.added DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }

    /** Loves or un-loves a song. Returns whether it's loved now. */
    fun toggle(track: Track): Boolean {
        val now = !isLoved(track.id)
        db.writableDatabase.tx {
            if (now) {
                db.putTracks(this, listOf(track))
                insertWithOnConflict(
                    "loved",
                    null,
                    ContentValues().apply {
                        put("track_id", track.id)
                        put("added", System.currentTimeMillis())
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            } else {
                delete("loved", "track_id = ?", arrayOf(track.id))
            }
        }
        cache = null
        Changes.loved()
        return now
    }
}

/** What you played, newest first, for Home. */
object History {
    private const val KEEP = 200

    fun add(track: Track) {
        db.writableDatabase.tx {
            db.putTracks(this, listOf(track))
            insertWithOnConflict(
                "history",
                null,
                ContentValues().apply {
                    put("track_id", track.id)
                    put("played", System.currentTimeMillis())
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            execSQL("DELETE FROM history WHERE track_id NOT IN (SELECT track_id FROM history ORDER BY played DESC LIMIT $KEEP)")
        }
        Changes.history()
    }

    fun recent(limit: Int): List<Track> =
        db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM history h JOIN tracks t ON t.id = h.track_id ORDER BY h.played DESC LIMIT $limit",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }
}

/** Songs YouTube suggests after one you played ("Because you played..."). */
object Related {
    fun save(seed: Track, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        db.writableDatabase.tx {
            delete("related", null, null)
            db.putTracks(this, tracks)
            db.putTracks(this, listOf(seed))
            tracks.forEachIndexed { i, t ->
                insert(
                    "related",
                    null,
                    ContentValues().apply {
                        put("seed", seed.id)
                        put("pos", i)
                        put("track_id", t.id)
                    },
                )
            }
        }
        Changes.history()
    }

    /** The song the suggestions came from, and the suggestions. */
    fun latest(): Pair<Track, List<Track>>? {
        val seedId = db.readableDatabase.rawQuery("SELECT seed FROM related LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return null
        val seed = db.track(seedId) ?: return null
        val list = db.readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM related r JOIN tracks t ON t.id = r.track_id ORDER BY r.pos",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }
        return seed to list
    }
}

/** Songs: lookups shared by everything. */
object Tracks {
    fun get(id: String): Track? = db.track(id)
    fun put(tracks: Collection<Track>) = db.writableDatabase.tx { db.putTracks(this, tracks) }
    fun setVideo(trackId: String, videoId: String, art: String?) = db.setVideo(trackId, videoId, art)
}

/** A song whose file is gone from the phone: out of playlists, Loved songs and history. Blocking. */
object Forget {
    fun everywhere(trackId: String) {
        val args = arrayOf(trackId)
        var lists = 0
        var loved = 0
        var history = 0
        db.writableDatabase.tx {
            lists = delete("playlist_items", "track_id = ?", args)
            loved = delete("loved", "track_id = ?", args)
            history = delete("history", "track_id = ?", args)
        }
        if (lists > 0) Changes.playlists()
        if (loved > 0) {
            Loved.dropCache()
            Changes.loved()
        }
        if (history > 0) Changes.history()
    }
}
