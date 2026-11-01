package io.github.ferasbusiness666.segno.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.ferasbusiness666.segno.core.Track

/** Music files on the phone, read from Android's media library (MediaStore). Blocking calls. */
object LocalMusic {
    data class Album(val id: Long, val title: String, val artist: String, val songs: Int) {
        val art: String get() = "content://media/external/audio/albumart/$id"
    }

    data class Artist(val id: Long, val name: String, val songs: Int, val albums: Int)

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private const val UNKNOWN = "<unknown>"

    private fun clean(s: String?) = s?.takeIf { it.isNotBlank() && it != UNKNOWN }.orEmpty()

    private val SONG_COLS = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
    )

    private const val MUSIC = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    fun songs(context: Context): List<Track> = query(context, MUSIC, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")

    fun albumSongs(context: Context, albumId: Long): List<Track> = query(
        context,
        "$MUSIC AND ${MediaStore.Audio.Media.ALBUM_ID} = ?",
        arrayOf(albumId.toString()),
        "${MediaStore.Audio.Media.TRACK}, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
    )

    fun artistSongs(context: Context, artistId: Long): List<Track> = query(
        context,
        "$MUSIC AND ${MediaStore.Audio.Media.ARTIST_ID} = ?",
        arrayOf(artistId.toString()),
        "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}",
    )

    private fun query(context: Context, where: String, args: Array<String>?, order: String): List<Track> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Track>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, SONG_COLS, where, args, order)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                out += Track(
                    id = Track.LOCAL + id,
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Unknown song",
                    artist = clean(c.getString(2)),
                    album = clean(c.getString(3)),
                    albumId = c.getLong(4),
                    durationMs = c.getLong(5),
                    uri = uri.toString(),
                )
            }
        }
        return out
    }

    fun albums(context: Context): List<Album> {
        if (!hasPermission(context)) return emptyList()
        val cols = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
        )
        val out = ArrayList<Album>()
        context.contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            cols,
            null,
            null,
            "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = clean(c.getString(1))
                if (title.isEmpty()) continue
                out += Album(c.getLong(0), title, clean(c.getString(2)), c.getInt(3))
            }
        }
        return out
    }

    fun artists(context: Context): List<Artist> {
        if (!hasPermission(context)) return emptyList()
        val cols = arrayOf(
            MediaStore.Audio.Artists._ID,
            MediaStore.Audio.Artists.ARTIST,
            MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
            MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
        )
        val out = ArrayList<Artist>()
        context.contentResolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            cols,
            null,
            null,
            "${MediaStore.Audio.Artists.ARTIST} COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext()) {
                out += Artist(c.getLong(0), clean(c.getString(1)).ifEmpty { "Unknown artist" }, c.getInt(2), c.getInt(3))
            }
        }
        return out
    }
}
