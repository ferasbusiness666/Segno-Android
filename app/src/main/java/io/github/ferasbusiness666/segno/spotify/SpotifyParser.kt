package io.github.ferasbusiness666.segno.spotify

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import io.github.ferasbusiness666.segno.core.Track

/** A playlist or album found on another site. */
data class RemotePlaylist(
    val title: String,
    val owner: String,
    /** The link it came from, to know whether it's already saved. */
    val url: String,
    val cover: String,
    val site: String,
)

class SpotifyException(message: String) : Exception(message)

/**
 * Reading Spotify links. Spotify's audio is locked, so nothing can download it. Instead the song
 * names are read from Spotify's public embed page, and each song is found on YouTube when it's
 * played or downloaded. Ported from desktop Segno's spotify.rs.
 */
object SpotifyParser {
    fun isSpotify(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("open.spotify.com/") || u.startsWith("spotify:") || u.contains("spotify.link/")
    }

    /** ("playlist" | "album" | "track", id) from any form of Spotify link. */
    fun parseLink(url: String): Pair<String, String>? {
        val u = url.trim()
        if (u.startsWith("spotify:")) {
            val parts = u.removePrefix("spotify:").split(':')
            if (parts.size < 2) return null
            return parts[0] to parts[1]
        }
        val after = u.split("open.spotify.com/").getOrNull(1) ?: return null
        val segs = after.split('/', '?', '#').filter { it.isNotEmpty() }.toMutableList()
        if (segs.isEmpty()) return null
        var kind = segs.removeAt(0)
        if (kind.startsWith("intl-") || kind == "embed") {
            if (segs.isEmpty()) return null
            kind = segs.removeAt(0)
        }
        val id = segs.firstOrNull() ?: return null
        return if (kind in setOf("playlist", "album", "track")) kind to id else null
    }

    /** The song ids in text copied out of Spotify, in order, once each. */
    fun trackIdsIn(text: String): List<String> = idsIn(text, "track")

    /**
     * Ids of one [kind] ("track", "playlist") in Spotify links and URIs anywhere in [text]:
     * "open.spotify.com/track/ID", ".../intl-de/track/ID", "spotify:track:ID", and the
     * "/track/ID" links in Spotify's own pages.
     */
    fun idsIn(text: String, kind: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        fun idAt(rest: String): String? {
            val id = rest.takeWhile { it.isLetterOrDigit() && it.code < 128 }
            return if (id.length == 22) id else null
        }
        var from = 0
        while (true) {
            val i = text.indexOf("spotify.com/", from)
            if (i < 0) break
            from = i + 1
            var rest = text.substring(i + "spotify.com/".length)
            if (rest.startsWith("intl-") || rest.startsWith("embed/")) {
                rest = rest.substringAfter('/', "")
            }
            if (rest.startsWith("$kind/")) {
                idAt(rest.removePrefix("$kind/"))?.let { found += i to it }
            }
        }
        for (prefix in listOf("spotify:$kind:", "\"/$kind/")) {
            var start = 0
            while (true) {
                val i = text.indexOf(prefix, start)
                if (i < 0) break
                start = i + 1
                idAt(text.substring(i + prefix.length))?.let { found += i to it }
            }
        }
        val ids = mutableListOf<String>()
        for ((_, id) in found.sortedBy { it.first }) {
            if (id !in ids) ids += id
        }
        return ids
    }

    /** Short share links ("spotify.link/...") lead to a page that names the real link. */
    fun findCanonical(page: String): String? {
        val i = page.indexOf("https://open.spotify.com/")
        if (i < 0) return null
        val tail = page.substring(i)
        val end = tail.indexOfFirst { it == '"' || it == '\'' || it == '<' || it == ' ' }
        return if (end < 0) tail else tail.substring(0, end)
    }

    /** The JSON Spotify's embed page is built from. */
    fun pageJson(page: String): JsonObject? {
        val i = page.indexOf("id=\"__NEXT_DATA__\"")
        if (i < 0) return null
        val gt = page.indexOf('>', i)
        if (gt < 0) return null
        val end = page.indexOf("</script>", gt)
        if (end < 0) return null
        return try {
            JsonParser.`object`().from(page.substring(gt + 1, end))
        } catch (e: Exception) {
            null
        }
    }

    /** The object describing the song, album or playlist, wherever it sits in the page's JSON. */
    fun findEntity(v: JsonObject): JsonObject? {
        val direct = v.getObject("props").getObject("pageProps").getObject("state").getObject("data")
        if (direct.has("entity") && direct["entity"] is JsonObject) return direct.getObject("entity")
        fun walk(o: Any?): JsonObject? = when (o) {
            is JsonObject -> {
                if (o.containsKey("trackList") ||
                    (o.containsKey("type") && (o.containsKey("name") || o.containsKey("title")) && o.containsKey("uri"))
                ) {
                    o
                } else {
                    o.values.firstNotNullOfOrNull { walk(it) }
                }
            }
            is JsonArray -> o.firstNotNullOfOrNull { walk(it) }
            else -> null
        }
        return walk(v)
    }

    private fun text(v: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> v.getString(k)?.trim()?.takeIf { it.isNotEmpty() } } ?: ""

    /** The largest cover picture listed. */
    fun cover(v: JsonObject): String {
        val lists = listOf(
            v.getObject("coverArt").getArray("sources", null),
            v.getObject("visualIdentity").getArray("image", null),
            v.getArray("images", null),
        )
        for (list in lists) {
            if (list == null) continue
            val best = list.filterIsInstance<JsonObject>().maxByOrNull {
                if (it.has("width")) it.getLong("width") else it.getLong("maxWidth", 0)
            }
            val url = best?.getString("url")
            if (!url.isNullOrEmpty()) return url
        }
        return ""
    }

    fun artists(v: JsonObject): String {
        val list = v.getArray("artists", null)
        if (list != null) {
            val names = list.filterIsInstance<JsonObject>().mapNotNull { it.getString("name") }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return text(v, "subtitle", "artist")
    }

    private fun durationMs(v: JsonObject): Long {
        val d = v["duration"]
        return when (d) {
            is Number -> d.toLong()
            is JsonObject -> d.getLong("totalMilliseconds", 0)
            else -> 0
        }
    }

    private fun trackId(v: JsonObject): String = text(v, "uri").substringAfterLast(':')

    fun song(id: String, title: String, artist: String, durationMs: Long, cover: String, album: String): Track {
        // The first artist is enough to find it; extra featured names tend to mislead the search.
        val main = artist.split(',').first().trim()
        return Track(
            id = Track.SP + id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            art = cover,
            find = "$main - $title audio",
        )
    }

    /** Reads an embed page. Throws [SpotifyException] with a message to show. */
    fun parseEmbed(page: String, kind: String, id: String, link: String): Pair<List<Track>, RemotePlaylist?> {
        val json = pageJson(page) ?: throw SpotifyException("Spotify changed its page, so Segno couldn't read this link.")
        val e = findEntity(json) ?: throw SpotifyException("Couldn't read this Spotify link.")
        val name = text(e, "name", "title")
        val pic = cover(e)
        if (kind == "track") {
            val tid = trackId(e).ifEmpty { id }
            if (name.isEmpty()) throw SpotifyException("Couldn't read this Spotify song.")
            return listOf(song(tid, name, artists(e), durationMs(e), pic, "")) to null
        }
        val list = e.getArray("trackList", null)
            ?: throw SpotifyException("Couldn't read the songs in this Spotify link.")
        // Album songs share the album's cover; playlist songs get their YouTube picture later.
        val albumCover = if (kind == "album") pic else ""
        val albumName = if (kind == "album") name else ""
        val items = list.filterIsInstance<JsonObject>().mapNotNull { t ->
            val title = text(t, "title", "name")
            val tid = trackId(t)
            if (title.isEmpty() || tid.isEmpty()) null else song(tid, title, artists(t), durationMs(t), albumCover, albumName)
        }
        if (items.isEmpty()) throw SpotifyException("This Spotify link has no songs Segno can read.")
        val info = RemotePlaylist(
            title = name.ifEmpty { "Spotify playlist" },
            owner = text(e, "subtitle"),
            url = link,
            cover = pic,
            site = "Spotify",
        )
        return items to info
    }
}
