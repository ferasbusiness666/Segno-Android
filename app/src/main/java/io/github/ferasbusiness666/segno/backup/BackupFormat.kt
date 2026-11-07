package io.github.ferasbusiness666.segno.backup

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import io.github.ferasbusiness666.segno.core.Track
import java.security.MessageDigest

/**
 * The backup file: playlists, Loved songs, settings and the queue in one JSON file that Segno on
 * Android, Windows or a new PC can read. docs/backup-format.md describes it. Plain Kotlin, so it
 * can be tested without a phone.
 */
object BackupFormat {
    /** The format version written at the top ("segno_backup": 1). Raise it only for breaking changes. */
    const val VERSION = 1

    /** A song, described so another device can find it again. */
    data class Song(
        /** "youtube", "spotify" or "file". */
        val source: String,
        val title: String,
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0,
        val art: String = "",
        /** YouTube: the video. Spotify: the YouTube video picked for it, if any. */
        val youtubeId: String = "",
        val spotifyId: String = "",
        /** Spotify: the words to search YouTube for. */
        val search: String = "",
        /** Files: where it was on the device that made the backup. Only a hint. */
        val path: String = "",
    )

    data class Playlist(
        val name: String,
        val songs: List<Song>,
        /** The link it was imported from, its site ("Spotify", "YouTube") and cover, if any. */
        val sourceUrl: String = "",
        val site: String = "",
        val cover: String = "",
    )

    data class Queue(val songs: List<Song>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: String)

    data class Settings(
        val autoplay: Boolean? = null,
        val checkUpdates: Boolean? = null,
        /** "youtube_music" or "youtube". */
        val search: String? = null,
        val libraryPage: String? = null,
        val recentSearches: List<String>? = null,
    )

    data class Data(
        val app: String,
        val appVersion: String,
        /** ISO 8601, UTC: "2026-11-07T10:20:30Z". */
        val created: String,
        val settings: Settings,
        val playlists: List<Playlist>,
        val loved: List<Song>,
        val queue: Queue? = null,
    )

    /** Why a file can't be restored, in words to show. */
    class Unreadable(val reason: Reason) : Exception(reason.name)

    enum class Reason { NotABackup, Newer, Damaged }

    // Writing -----------------------------------------------------------------------------------

    fun write(d: Data): String {
        val w = JsonWriter.indent("  ").string().`object`()
        w.value("segno_backup", VERSION)
        w.value("app", d.app)
        w.value("app_version", d.appVersion)
        w.value("created", d.created)
        w.`object`("settings")
        d.settings.autoplay?.let { w.value("autoplay", it) }
        d.settings.checkUpdates?.let { w.value("check_updates", it) }
        d.settings.search?.let { w.value("search", it) }
        d.settings.libraryPage?.let { w.value("library_page", it) }
        d.settings.recentSearches?.let { list ->
            w.array("recent_searches")
            list.forEach { w.value(it) }
            w.end()
        }
        w.end()
        w.array("playlists")
        for (p in d.playlists) {
            w.`object`()
            w.value("name", p.name)
            if (p.sourceUrl.isNotEmpty()) w.value("source_url", p.sourceUrl)
            if (p.site.isNotEmpty()) w.value("site", p.site)
            if (p.cover.isNotEmpty()) w.value("cover", p.cover)
            w.array("songs")
            p.songs.forEach { writeSong(w, it) }
            w.end()
            w.end()
        }
        w.end()
        w.array("loved")
        d.loved.forEach { writeSong(w, it) }
        w.end()
        d.queue?.let { q ->
            w.`object`("queue")
            w.value("index", q.index)
            w.value("position_ms", q.positionMs)
            w.value("shuffle", q.shuffle)
            w.value("repeat", q.repeat)
            w.array("songs")
            q.songs.forEach { writeSong(w, it) }
            w.end()
            w.end()
        }
        w.end()
        return w.done() + "\n"
    }

    private fun writeSong(w: com.grack.nanojson.JsonStringWriter, s: Song) {
        w.`object`()
        w.value("source", s.source)
        if (s.youtubeId.isNotEmpty()) w.value("youtube_id", s.youtubeId)
        if (s.spotifyId.isNotEmpty()) w.value("spotify_id", s.spotifyId)
        w.value("title", s.title)
        if (s.artist.isNotEmpty()) w.value("artist", s.artist)
        if (s.album.isNotEmpty()) w.value("album", s.album)
        if (s.durationMs > 0) w.value("duration_ms", s.durationMs)
        if (s.art.isNotEmpty()) w.value("art", s.art)
        if (s.search.isNotEmpty()) w.value("search", s.search)
        if (s.path.isNotEmpty()) w.value("path", s.path)
        w.end()
    }

    // Reading -----------------------------------------------------------------------------------

    /** Reads a backup. Throws [Unreadable] for files that aren't one, are damaged or are newer. */
    fun read(text: String): Data {
        val o: JsonObject = try {
            JsonParser.`object`().from(text)
        } catch (e: Exception) {
            throw Unreadable(if (text.trimStart().startsWith("{")) Reason.Damaged else Reason.NotABackup)
        }
        val version = o["segno_backup"] as? Number ?: throw Unreadable(Reason.NotABackup)
        if (version.toInt() > VERSION) throw Unreadable(Reason.Newer)
        if (version.toInt() < 1) throw Unreadable(Reason.NotABackup)
        return try {
            val s = o.getObject("settings") ?: JsonObject()
            Data(
                app = o.getString("app").orEmpty(),
                appVersion = o.getString("app_version").orEmpty(),
                created = o.getString("created").orEmpty(),
                settings = Settings(
                    autoplay = s["autoplay"] as? Boolean,
                    checkUpdates = s["check_updates"] as? Boolean,
                    search = s["search"] as? String,
                    libraryPage = s["library_page"] as? String,
                    recentSearches = (s["recent_searches"] as? JsonArray)?.filterIsInstance<String>(),
                ),
                playlists = objects(o.getArray("playlists")).mapNotNull { p ->
                    val name = p.getString("name").orEmpty().trim()
                    if (name.isEmpty()) {
                        null
                    } else {
                        Playlist(
                            name = name,
                            songs = songs(p.getArray("songs")),
                            sourceUrl = p.getString("source_url").orEmpty(),
                            site = p.getString("site").orEmpty(),
                            cover = p.getString("cover").orEmpty(),
                        )
                    }
                },
                loved = songs(o.getArray("loved")),
                queue = o.getObject("queue")?.let { q ->
                    Queue(
                        songs = songs(q.getArray("songs")),
                        index = q.getInt("index", 0),
                        positionMs = q.getLong("position_ms", 0),
                        shuffle = q.getBoolean("shuffle", false),
                        repeat = q.getString("repeat") ?: "off",
                    )
                },
            )
        } catch (e: Exception) {
            throw Unreadable(Reason.Damaged)
        }
    }

    private fun objects(a: JsonArray?): List<JsonObject> = a.orEmpty().filterIsInstance<JsonObject>()

    /** Songs this version understands; others (from a newer app) are left out. */
    private fun songs(a: JsonArray?): List<Song> = objects(a).mapNotNull { s ->
        val source = s.getString("source").orEmpty()
        val song = Song(
            source = source,
            title = s.getString("title").orEmpty(),
            artist = s.getString("artist").orEmpty(),
            album = s.getString("album").orEmpty(),
            durationMs = s.getLong("duration_ms", 0).coerceAtLeast(0),
            art = s.getString("art").orEmpty(),
            youtubeId = s.getString("youtube_id").orEmpty(),
            spotifyId = s.getString("spotify_id").orEmpty(),
            search = s.getString("search").orEmpty(),
            path = s.getString("path").orEmpty(),
        )
        when (source) {
            YOUTUBE -> song.takeIf { it.youtubeId.isNotEmpty() }
            SPOTIFY -> song.takeIf { it.spotifyId.isNotEmpty() }
            FILE -> song.takeIf { it.title.isNotEmpty() || it.path.isNotEmpty() }
            else -> null
        }
    }

    // Songs and Segno's tracks ------------------------------------------------------------------

    const val YOUTUBE = "youtube"
    const val SPOTIFY = "spotify"
    const val FILE = "file"

    /** A song for the backup. [path]: where a phone file is, if known. */
    fun song(t: Track, path: String? = null): Song = when (t.kind) {
        Track.Kind.YouTube -> Song(YOUTUBE, t.title, t.artist, t.album, t.durationMs, t.art, youtubeId = t.key)
        Track.Kind.Spotify -> Song(SPOTIFY, t.title, t.artist, t.album, t.durationMs, t.art, youtubeId = t.video, spotifyId = t.key, search = t.find)
        Track.Kind.Local -> Song(
            FILE, t.title, t.artist, t.album, t.durationMs,
            path = path ?: t.uri.takeIf { it.startsWith("file://") }?.removePrefix("file://").orEmpty(),
        )
    }

    /** Segno's track for a backup song. Phone files go through [findFile]; not found, they're kept as missing. */
    fun track(s: Song, findFile: (Song) -> Track?): Track = when (s.source) {
        YOUTUBE -> Track.youtube(s.youtubeId, s.title, s.artist, s.durationMs, s.art).copy(album = s.album)
        SPOTIFY -> Track(
            id = Track.SP + s.spotifyId,
            title = s.title,
            artist = s.artist,
            album = s.album,
            durationMs = s.durationMs,
            art = s.art,
            find = s.search.ifEmpty { listOf(s.artist, s.title).filter { it.isNotEmpty() }.joinToString(" ") },
            video = s.youtubeId,
        )
        else -> findFile(s) ?: missing(s)
    }

    /** A phone file that isn't on this phone: kept, with a stable id, so it can be shown and backed up again. */
    fun missing(s: Song): Track {
        val key = listOf(s.title, s.artist, s.durationMs.toString(), s.path).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return Track(
            id = Track.MISSING + hash,
            title = s.title.ifEmpty { s.path.substringAfterLast('/').substringBeforeLast('.') },
            artist = s.artist,
            album = s.album,
            durationMs = s.durationMs,
            uri = if (s.path.isNotEmpty()) "file://" + s.path else "",
        )
    }

    /** "off", "all" or "one" for Media3's repeat modes 0, 2 and 1. */
    fun repeatName(mode: Int) = when (mode) {
        1 -> "one"
        2 -> "all"
        else -> "off"
    }

    fun repeatMode(name: String) = when (name) {
        "one" -> 1
        "all" -> 2
        else -> 0
    }
}
