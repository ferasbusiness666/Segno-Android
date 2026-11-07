package io.github.ferasbusiness666.segno.lyrics

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * YouTube Music: search songs, a song's details, and its lyrics, timed when YouTube Music has
 * them. The same requests its own apps make (no account). Ported from desktop (src/ytmusic.rs):
 * answers are read loosely, by looking for parts by name anywhere, so small layout changes don't
 * break it. Checked against the real answers in the test resources. Blocking calls.
 */
object YtMusic {
    private const val API = "https://music.youtube.com/youtubei/v1/"
    /** YouTube Music's own "Songs" filter. */
    private const val SONGS_ONLY = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    /** Timed lyrics only come to YouTube Music's Android app. */
    private val ANDROID = "ANDROID_MUSIC" to "7.21.50"
    private const val SECS = 10L
    private val HEADERS = listOf("Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/")

    /** A song on YouTube Music. */
    data class Song(
        val id: String,
        val title: String,
        /** All its artists, as YouTube Music writes them ("Daft Punk, Pharrell Williams & Nile Rodgers"). */
        val artist: String,
        val album: String = "",
        val duration: Float = 0f,
        val thumb: String = "",
    )

    /** What YouTube Music knows about one video id. */
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val duration: Float,
        /** An official song upload (the album audio); otherwise a music video or anyone's upload. */
        val song: Boolean,
        /** What to ask for its lyrics, when it has some. */
        val lyrics: String?,
    )

    /** A song's words. */
    sealed interface Words {
        /** Timed to the song's album audio. */
        data class Timed(val lines: List<Line>) : Words
        data class Plain(val text: String) : Words
    }

    /** The date as YouTube Music's web app writes it in its version ("20261107"). */
    fun today(): String = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun ask(endpoint: String, client: Pair<String, String>, body: JsonObject): Any? {
        body["context"] = JsonObject().apply {
            put("client", JsonObject().apply {
                put("clientName", client.first)
                put("clientVersion", client.second)
                put("hl", "en")
                put("gl", "US")
            })
        }
        val text = Web.postJson("$API$endpoint?prettyPrint=false", JsonWriter.string(body), HEADERS, SECS)
        return Web.json(text)
    }

    private fun web() = "WEB_REMIX" to "1.${today()}.01.00"

    /** Songs YouTube Music finds for some words, best first. */
    fun searchSongs(query: String): List<Song> =
        songsIn(ask("search", web(), JsonObject().apply { put("query", query); put("params", SONGS_ONLY) }))

    /** What YouTube Music knows about a video id, and where its lyrics are. */
    fun track(id: String): Track {
        val body = JsonObject().apply {
            put("videoId", id)
            put("playlistId", "RDAMVM$id")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        }
        return trackIn(ask("next", web(), body), id) ?: throw SourceException("YouTube Music didn't know this song.")
    }

    /** A song's words (null: YouTube Music has none for it). */
    fun lyrics(browseId: String): Words? = wordsIn(ask("browse", ANDROID, JsonObject().apply { put("browseId", browseId) }))

    // Reading the answers --------------------------------------------------------------------------

    /** Text written as `{"runs": [{"text": …}, …]}` or `{"simpleText": …}`. */
    internal fun text(v: Any?): String {
        J.str(J.get(v, "simpleText"))?.let { return it.trim() }
        val runs = J.arr(J.get(v, "runs")) ?: return ""
        return runs.mapNotNull { J.str(J.get(it, "text")) }.joinToString("").trim()
    }

    /** "4:09" or "1:02:03" in seconds. */
    internal fun clock(s: String): Float? {
        val parts = s.trim().split(':')
        if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || !p.all { it in '0'..'9' } }) return null
        return parts.fold(0f) { acc, p -> acc * 60f + (p.toFloatOrNull() ?: 0f) }
    }

    /** The biggest picture, asked for at a size good for covers. */
    private fun cover(v: Any?): String {
        val first = J.arr(J.find(v, "thumbnails").firstOrNull())
        val url = J.str(J.get(first?.lastOrNull(), "url")).orEmpty()
        // Google picture links end with their size ("=w120-h120-l90-rj"): ask for a bigger one.
        val i = url.lastIndexOf("=w")
        return if (i >= 0 && url.contains("googleusercontent.com")) url.substring(0, i) + "=w544-h544-l90-rj" else url
    }

    /** The songs in a search answer, once each. */
    internal fun songsIn(answer: Any?): List<Song> {
        val seen = HashSet<String>()
        return J.find(answer, "musicResponsiveListItemRenderer").mapNotNull(::songFrom).filter { seen.add(it.id) }
    }

    /** One row: the title, then "Artists • Album • 3:45". */
    private fun songFrom(r: Any?): Song? {
        val id = (J.str(J.get(J.get(r, "playlistItemData"), "videoId")) ?: J.str(J.get(J.first(r, "watchEndpoint"), "videoId"))) ?: return null
        val cols = J.arr(J.get(r, "flexColumns"))?.map { text(J.get(J.get(it, "musicResponsiveListItemFlexColumnRenderer"), "text")) } ?: return null
        val title = cols.firstOrNull() ?: return null
        val parts = cols.getOrNull(1)?.split(" • ").orEmpty()
        val duration = parts.asReversed().firstNotNullOfOrNull { clock(it) } ?: return null
        val rest = parts.filter { clock(it) == null }
        if (title.isEmpty() || id.length != 11) return null
        return Song(id, title, rest.getOrNull(0).orEmpty(), rest.getOrNull(1).orEmpty(), duration, cover(J.get(r, "thumbnail")))
    }

    /** The video [id] in a "next" answer: its names, length, kind and lyrics. */
    internal fun trackIn(answer: Any?, id: String): Track? {
        val panels = J.find(answer, "playlistPanelVideoRenderer")
        val p = panels.firstOrNull { J.str(J.get(it, "videoId")) == id } ?: panels.firstOrNull() ?: return null
        val byline = text(J.get(p, "longBylineText") ?: J.get(p, "shortBylineText"))
        val kind = J.get(p, "navigationEndpoint")?.let { J.str(J.first(it, "musicVideoType")) }.orEmpty()
        val lyrics = J.find(answer, "tabRenderer").firstNotNullOfOrNull { t ->
            val b = J.get(J.get(t, "endpoint"), "browseEndpoint") ?: return@firstNotNullOfOrNull null
            if (J.str(J.first(b, "pageType")) == "MUSIC_PAGE_TYPE_TRACK_LYRICS") J.str(J.get(b, "browseId")) else null
        }
        return Track(
            id = J.str(J.get(p, "videoId")) ?: id,
            title = text(J.get(p, "title")),
            artist = byline.split(" • ").first().trim(),
            duration = J.get(p, "lengthText")?.let { clock(text(it)) } ?: 0f,
            song = kind == "MUSIC_VIDEO_TYPE_ATV",
            lyrics = lyrics,
        )
    }

    /** The words in a lyrics answer: timed when every line has its time, plain otherwise. */
    internal fun wordsIn(answer: Any?): Words? {
        val lines = J.arr(J.first(answer, "timedLyricsData"))
        if (!lines.isNullOrEmpty()) {
            val timed = ArrayList<Line>()
            var ok = true
            for (l in lines) {
                val at = J.get(J.get(l, "cueRange"), "startTimeMilliseconds")
                val ms = J.str(at)?.toDoubleOrNull() ?: J.num(at)?.toDouble()
                if (ms == null) {
                    ok = false
                    break
                }
                timed += Line(ms.toFloat() / 1000f, J.str(J.get(l, "lyricLine")).orEmpty())
            }
            return if (ok) Words.Timed(timed) else Words.Plain(lines.mapNotNull { J.str(J.get(it, "lyricLine")) }.joinToString("\n"))
        }
        val shelf = J.first(answer, "musicDescriptionShelfRenderer") ?: return null
        val plain = text(J.get(shelf, "description"))
        return if (plain.isNotBlank()) Words.Plain(plain) else null
    }
}
