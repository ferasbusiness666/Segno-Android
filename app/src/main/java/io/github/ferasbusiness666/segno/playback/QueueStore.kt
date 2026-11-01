package io.github.ferasbusiness666.segno.playback

import android.content.Context
import io.github.ferasbusiness666.segno.core.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Remembers the queue between runs, so the last song is there when Segno opens. */
object QueueStore {
    data class Saved(
        val tracks: List<Track>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeat: Int,
    )

    private fun file(context: Context) = File(context.filesDir, "queue.json")

    /** Blocking. */
    fun save(context: Context, saved: Saved) {
        val arr = JSONArray()
        for (t in saved.tracks.take(2000)) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("artist", t.artist)
                    .put("album", t.album)
                    .put("duration", t.durationMs)
                    .put("art", t.art)
                    .put("find", t.find)
                    .put("video", t.video)
                    .put("uri", t.uri)
                    .put("albumId", t.albumId),
            )
        }
        val json = JSONObject()
            .put("index", saved.index)
            .put("position", saved.positionMs)
            .put("shuffle", saved.shuffle)
            .put("repeat", saved.repeat)
            .put("tracks", arr)
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(f)
    }

    /** Blocking. Null when nothing was saved or the file can't be read. */
    fun load(context: Context): Saved? = try {
        val f = file(context)
        if (!f.exists()) {
            null
        } else {
            val json = JSONObject(f.readText())
            val arr = json.getJSONArray("tracks")
            val tracks = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Track(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("duration"),
                    art = o.optString("art"),
                    find = o.optString("find"),
                    video = o.optString("video"),
                    uri = o.optString("uri"),
                    albumId = o.optLong("albumId"),
                )
            }
            if (tracks.isEmpty()) {
                null
            } else {
                Saved(
                    tracks = tracks,
                    index = json.optInt("index").coerceIn(0, tracks.size - 1),
                    positionMs = json.optLong("position").coerceAtLeast(0),
                    shuffle = json.optBoolean("shuffle"),
                    repeat = json.optInt("repeat"),
                )
            }
        }
    } catch (e: Exception) {
        null
    }
}
