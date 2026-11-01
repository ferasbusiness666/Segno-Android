package io.github.ferasbusiness666.segno.youtube

/**
 * Picture addresses at the size they're shown, so weak phones never download or decode more
 * than they need.
 */
object Thumbs {
    private val GOOGLE_SIZE = Regex("=(w\\d+-h\\d+|s\\d+)[^/]*$")
    private val YTIMG = Regex("^(https?://i\\d?\\.ytimg\\.com/vi(?:_webp)?/[A-Za-z0-9_-]{11}/)[^/?]+")

    /** A YouTube video's picture. */
    fun video(videoId: String, big: Boolean = false): String =
        "https://i.ytimg.com/vi/$videoId/" + if (big) "hq720.jpg" else "mqdefault.jpg"

    /** [url] at about [px] pixels wide. Unknown sites come back as they are. */
    fun sized(url: String, px: Int): String {
        if (url.isEmpty()) return url
        if (url.contains("googleusercontent.com/") || url.contains("ggpht.com/")) {
            val size = px.coerceIn(60, 1200)
            return if (GOOGLE_SIZE.containsMatchIn(url)) {
                url.replace(GOOGLE_SIZE, "=w$size-h$size-l90-rj")
            } else {
                "$url=w$size-h$size-l90-rj"
            }
        }
        val m = YTIMG.find(url)
        if (m != null) {
            return m.groupValues[1] + if (px <= 360) "mqdefault.jpg" else "hq720.jpg"
        }
        return url
    }

    /** For ytimg pictures: the small one, used when the big one doesn't exist. */
    fun fallback(url: String): String? {
        val m = YTIMG.find(url) ?: return null
        val small = m.groupValues[1] + "mqdefault.jpg"
        return if (small != url) small else null
    }
}
