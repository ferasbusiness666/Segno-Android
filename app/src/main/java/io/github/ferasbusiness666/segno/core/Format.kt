package io.github.ferasbusiness666.segno.core

/** Small text helpers, the same as desktop's theme.rs. */
object Format {
    /** "3:05", or "1:02:03" past an hour. */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return if (s >= 3600) {
            "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        } else {
            "%d:%02d".format(s / 60, s % 60)
        }
    }

    /** "1,234" */
    fun digits(n: Int): String {
        val s = n.toString()
        val out = StringBuilder()
        s.forEachIndexed { i, c ->
            if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return out.toString()
    }

    /** "1 song", "12 songs" */
    fun plural(n: Int, one: String, many: String): String =
        if (n == 1) "1 $one" else "${digits(n)} $many"

    /** "12 songs, 48 min" / "1 song, 3 min" / "120 songs, 7 hr 5 min" */
    fun songsAndLength(n: Int, totalMs: Long): String {
        val songs = plural(n, "song", "songs")
        if (totalMs <= 0) return songs
        val mins = Math.round(totalMs / 60_000.0)
        val len = if (mins >= 60) "${mins / 60} hr ${mins % 60} min" else "${mins.coerceAtLeast(1)} min"
        return "$songs, $len"
    }

    /** "Artist · 3:05", leaving out what's missing. */
    fun sub(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

    /** YouTube's auto-generated "Artist - Topic" channels: just the artist. */
    fun cleanArtist(name: String?): String = name.orEmpty().removeSuffix(" - Topic").trim()

    /** A file name that works on every phone and computer. */
    fun safeFileName(name: String, maxLen: Int = 120): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        val cut = if (cleaned.length > maxLen) cleaned.take(maxLen).trim() else cleaned
        return cut.ifEmpty { "Song" }
    }
}
