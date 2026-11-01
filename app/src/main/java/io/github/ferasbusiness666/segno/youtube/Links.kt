package io.github.ferasbusiness666.segno.youtube

import java.net.URLDecoder

/** Reading YouTube links. Pure functions, so they can be tested. */
object Links {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isYouTube(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains("youtube-nocookie.com/")
    }

    /** Something that looks like a web link rather than words to search for. */
    fun looksLikeLink(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ')) return false
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("spotify:")) return true
        val host = t.substringBefore('/').lowercase()
        return host.contains('.') && (host.endsWith(".com") || host.endsWith(".be") || host.contains("youtube") || host.contains("spotify"))
    }

    /** A link with "https://" in front, if it was left out. */
    fun fullUrl(text: String): String {
        val t = text.trim()
        return if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("spotify:")) t else "https://$t"
    }

    private fun query(url: String, key: String): String? {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return null
        for (part in q.split('&')) {
            val k = part.substringBefore('=')
            if (k == key) {
                return try {
                    URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    /** The video id in a watch, share, Shorts or music link. */
    fun videoId(url: String): String? {
        if (!isYouTube(url)) return null
        query(url, "v")?.takeIf { ID.matches(it) }?.let { return it }
        val lower = url.lowercase()
        val markers = listOf("youtu.be/", "/shorts/", "/embed/", "/live/", "/v/")
        for (m in markers) {
            val i = lower.indexOf(m)
            if (i >= 0) {
                val id = url.substring(i + m.length).takeWhile { it != '?' && it != '&' && it != '/' && it != '#' }
                if (ID.matches(id)) return id
            }
        }
        return null
    }

    /** The playlist id ("PL...", "OLAK5uy_...") in a link, if there is one. */
    fun playlistId(url: String): String? {
        if (!isYouTube(url)) return null
        return query(url, "list")?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    /** A YouTube (or YouTube Music) channel link → its Playlists tab. Null for other links. */
    fun channelPlaylists(url: String): String? {
        val lower = url.lowercase()
        val i = lower.indexOf("youtube.com/")
        if (i < 0) return null
        val path = url.substring(i + "youtube.com/".length)
        val segs = path.split('/', '?', '#').filter { it.isNotEmpty() }
        val first = segs.firstOrNull() ?: return null
        val base = when {
            first.startsWith("@") -> first
            first in setOf("channel", "c", "user") -> "$first/${segs.getOrNull(1) ?: return null}"
            else -> return null
        }
        return "https://www.youtube.com/$base/playlists"
    }
}
