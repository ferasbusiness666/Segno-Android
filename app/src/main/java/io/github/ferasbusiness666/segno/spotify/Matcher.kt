package io.github.ferasbusiness666.segno.spotify

/**
 * How a Spotify song is matched to a YouTube video. Ported from desktop Segno (ytdlp.rs): of the
 * first results for "<artist> - <title> audio", take the first whose length is close to the real
 * song's (within about 15% plus 5 seconds), so a 10-minute live version or a 30-second clip
 * doesn't win, and that isn't another version (live, cover, sped up...) unless the song itself
 * is one. If nothing fits, the first result plays anyway.
 */
object Matcher {
    /** Kinds of other versions. A song whose own title says it's one of these keeps them in. */
    val KINDS = listOf(
        "live", "cover", "karaoke", "instrumental", "nightcore", "sped up", "slowed", "reverb", "8d", "remix", "acoustic",
    )

    /** How many search results are looked at, like desktop's "ytsearch5". */
    const val LOOK_AT = 5

    data class Candidate(val videoId: String, val title: String, val durationSec: Long)

    /**
     * A pattern for video titles that are another version of a song, leaving out the kinds the
     * song's own title says it is. Null when it is all of them.
     */
    fun otherVersions(songTitle: String): Regex? {
        val own = songTitle.lowercase()
        val skip = KINDS.filter { !own.contains(it) }
        if (skip.isEmpty()) return null
        return Regex("\\b(" + skip.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
    }

    /** The accepted length range in seconds for a song of [songSec] seconds. */
    fun window(songSec: Double): LongRange {
        val lo = (songSec * 0.85 - 5.0).coerceAtLeast(0.0).toLong()
        val hi = (songSec * 1.15 + 5.0).toLong()
        return lo..hi
    }

    /**
     * Picks the video for a song from search results (in YouTube's order). Returns null only when
     * there are no results.
     */
    fun pick(results: List<Candidate>, songTitle: String, songDurationMs: Long): Candidate? {
        val top = results.take(LOOK_AT)
        if (top.isEmpty()) return null
        if (songDurationMs <= 0) return top.first()
        val range = window(songDurationMs / 1000.0)
        val other = otherVersions(songTitle)
        return top.firstOrNull { c ->
            c.durationSec in range && (other == null || !other.containsMatchIn(c.title))
        } ?: top.first()
    }
}
