package io.github.ferasbusiness666.segno.lyrics

/**
 * NetEase Cloud Music: a huge library of timed lyrics (Arabic songs too), a backup when YouTube
 * Music and LRCLIB have nothing. Its public web API, the way its PC app asks. Unofficial: it can
 * change or block, so a failure here never stops the other places. Ported from desktop
 * (src/netease.rs). Blocking calls.
 */
object NetEase {
    private const val SECS = 8L
    private val HEADERS = listOf("Referer" to "https://music.163.com/", "Cookie" to "appver=8.2.30; os=pc")

    data class Song(val id: Long, val title: String, val artist: String, val duration: Float)

    /** Songs NetEase finds for some words. */
    fun search(query: String): List<Song> =
        songsIn(Web.json(Web.getWith("https://music.163.com/api/search/get?s=${Web.enc(query)}&type=1&offset=0&limit=8", HEADERS, SECS)))

    /** A song's timed lyrics as LRC text, without the credit lines NetEase adds (null: none). */
    fun lyrics(id: Long): String? =
        lrcIn(Web.json(Web.getWith("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1", HEADERS, SECS)))

    internal fun songsIn(v: Any?): List<Song> {
        val songs = J.arr(J.get(J.get(v, "result"), "songs")) ?: return emptyList()
        return songs.mapNotNull { s ->
            val artists = J.arr(J.get(s, "artists") ?: J.get(s, "ar")) ?: return@mapNotNull null
            val id = J.num(J.get(s, "id"))?.toLong() ?: return@mapNotNull null
            val name = J.str(J.get(s, "name")) ?: return@mapNotNull null
            Song(
                id = id,
                title = name,
                artist = artists.mapNotNull { J.str(J.get(it, "name")) }.joinToString(", "),
                duration = (J.num(J.get(s, "duration") ?: J.get(s, "dt"))?.toDouble() ?: 0.0).toFloat() / 1000f,
            )
        }
    }

    internal fun lrcIn(v: Any?): String? {
        if (J.get(v, "nolyric") == true) return null
        val lrc = J.str(J.get(J.get(v, "lrc"), "lyric")) ?: return null
        val kept = lrc.lines().filter { !isCredit(it) }
        // Something with a time and words left.
        val any = kept.any { l -> l.startsWith('[') && l.substringAfterLast(']').isNotBlank() }
        return if (any) kept.joinToString("\n") else null
    }

    /**
     * "[00:00.000] 作曲 : Name" and the like: who wrote, produced or mixed it, not words to sing.
     * The label before the colon is Chinese; sung lines rarely look like that.
     */
    internal fun isCredit(line: String): Boolean {
        val words = line.substringAfterLast(']').trim()
        val colon = words.indexOfAny(charArrayOf(':', '：'))
        if (colon < 0) return false
        val label = words.substring(0, colon).trim()
        return label.isNotEmpty() && label.codePointCount(0, label.length) <= 8 &&
            label.all { it in '一'..'鿿' || it == ' ' || it == '/' }
    }
}
