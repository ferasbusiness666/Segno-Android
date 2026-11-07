package io.github.ferasbusiness666.segno.lyrics

import io.github.ferasbusiness666.segno.lyrics.Names.Speed
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * LRCLIB (https://lrclib.net/docs): free timed lyrics, no account. Ported from desktop
 * (from_lrclib and choose in src/lyrics.rs): exact asks first, then searches, at most 8 requests
 * 250 ms apart, busy answers waited out, and slowed or sped-up versions stretched. Blocking.
 */
object Lrclib {
    /** A cut of the song more than this many seconds longer or shorter would drift out of sync. */
    const val MAX_DRIFT = 4f
    /** Most requests one song may cost (LRCLIB allows about 90 a minute). */
    const val MAX_REQUESTS = 8

    data class Entry(
        val track: String,
        val artist: String,
        val duration: Double?,
        val instrumental: Boolean = false,
        val synced: String? = null,
        val plain: String? = null,
    ) {
        fun syncedText(): String? = synced?.takeIf { it.isNotBlank() }
        fun plainText(): String? = plain?.takeIf { it.isNotBlank() }

        /** The words' keys of its timed and plain lyrics. */
        fun wordsKeys(): List<Long> = listOfNotNull(syncedText()?.let { Lyrics.parseLrc(it).wordsKey() }, plainText()?.let { Lyrics.plain(it).wordsKey() })

        /** How far its length is from [dur] (unknown lengths count as a near miss). */
        fun off(dur: Float): Float = if (duration != null && dur > 0f) abs(duration.toFloat() - dur) else MAX_DRIFT / 2f
    }

    /** LRCLIB said it's busy, twice and more: stop asking for now. */
    class Busy : IOException("LRCLIB is busy")

    /** The lyrics picked from what LRCLIB sent back. */
    sealed interface Pick {
        data class Synced(val text: String) : Pick
        /** The original's timing, stretched by this much for a slowed or sped-up version. */
        data class Stretched(val text: String, val ratio: Float) : Pick
        data class Plain(val text: String) : Pick
    }

    internal fun entry(v: Any?): Entry? {
        if (J.obj(v) == null) return null
        return Entry(
            track = J.str(J.get(v, "trackName")).orEmpty(),
            artist = J.str(J.get(v, "artistName")).orEmpty(),
            duration = J.num(J.get(v, "duration"))?.toDouble(),
            instrumental = J.get(v, "instrumental") == true,
            synced = J.str(J.get(v, "syncedLyrics")),
            plain = J.str(J.get(v, "plainLyrics")),
        )
    }

    /**
     * Pick the best lyrics among [entries] for a song of [dur] seconds (0: unknown) that goes by
     * any of [pairs]. Timed lyrics of the same length win. A slowed or sped-up song takes the
     * original's timing, stretched to its length. Otherwise plain lyrics of the closest length,
     * but only when the artist matched too: a song name alone is shared by too many songs.
     */
    fun choose(entries: List<Entry>, pairs: List<Pair<String, String>>, dur: Float, speed: Speed?, avoid: Long?): Pick? {
        fun fits(e: Entry) = !e.instrumental && !(avoid != null && avoid in e.wordsKeys()) &&
            pairs.any { (s, a) -> Names.sameSong(e.track, s) && (a.isEmpty() || Names.sameArtist(e.artist, a)) }
        fun artistKnown(e: Entry) = pairs.any { (s, a) -> a.isNotEmpty() && Names.sameSong(e.track, s) && Names.sameArtist(e.artist, a) }
        val ok = entries.filter(::fits)

        ok.mapNotNull { e -> e.syncedText()?.let { e.off(dur) to it } }
            .filter { it.first <= MAX_DRIFT }
            .minByOrNull { it.first }
            ?.let { return Pick.Synced(it.second) }

        if (speed != null && dur > 0f) {
            // The original's length: the one most entries agree on.
            val originals = ok.filter { Names.speedOf(it.track) == null }
                .mapNotNull { e -> val d = e.duration ?: return@mapNotNull null; val t = e.syncedText() ?: return@mapNotNull null; d.toLong() to t }
            var best: Triple<Int, Long, String>? = null
            for ((d, text) in originals) {
                val n = originals.count { abs(it.first - d) <= 1 }
                if (best == null || n > best.first) best = Triple(n, d, text)
            }
            best?.takeIf { it.second > 0 }?.let { (_, d, text) ->
                val ratio = dur / d.toFloat()
                val ok2 = when (speed) {
                    Speed.Slower -> ratio in 1.02f..1.6f
                    Speed.Faster -> ratio in 0.6f..0.98f
                }
                if (ok2) return Pick.Stretched(text, ratio)
            }
        }

        return ok.filter(::artistKnown).mapNotNull { e ->
            val text = e.plainText() ?: e.syncedText()?.let { s -> Lyrics.parseLrc(s).lines.joinToString("\n") { it.words } } ?: return@mapNotNull null
            e.off(dur) to text
        }.minByOrNull { it.first }?.let { Pick.Plain(it.second) }
    }

    /** What one request said. */
    private sealed interface Got {
        data class Body(val text: String) : Got
        data object Nothing : Got
    }

    /**
     * One request. LRCLIB is often busy: that gets up to three more tries, waiting what it asks
     * for and longer each time, plus a little randomness. A dropped connection gets one more try.
     * Throws [Busy] or IOException.
     */
    private fun ask(url: String, get: (String) -> Web.Answer): Got {
        val busyTries = 4
        var tries = 0
        while (true) {
            tries++
            val wait: Long = try {
                when (val a = get(url)) {
                    is Web.Answer.Text -> return Got.Body(a.text)
                    Web.Answer.NotFound -> return Got.Nothing
                    // A server error on one query; the next one may well work.
                    is Web.Answer.Broken -> return Got.Nothing
                    is Web.Answer.Busy -> if (tries < busyTries) maxOf(a.waitMs, 1000L shl (tries - 1)) else throw Busy()
                }
            } catch (e: Busy) {
                throw e
            } catch (e: IOException) {
                if (tries < 2) 1000L else throw e
            }
            Thread.sleep((wait * (1.0 + (System.nanoTime() % 300) / 1000.0)).toLong())
        }
    }

    /**
     * Ask LRCLIB, most likely names first, and stop at the first timed match. A failed request
     * only counts as an error if nothing was found at all. [get] is the request (swapped in tests).
     */
    fun find(q: Query, get: (String) -> Web.Answer = { Web.getApi(it) }, pause: (Long) -> Unit = { Thread.sleep(it) }): Lyrics? {
        val pairs = Names.namePairs(q.title, q.artist, q.fromVideo)
        val (song, artist) = pairs.firstOrNull() ?: return null
        val dur = q.duration
        val speed = Names.speedOf(q.title)

        // Exact asks first: LRCLIB matches the names itself, and a length within 2 seconds.
        val exact = ArrayList<Pair<String, String>>()
        for ((s, a) in pairs.take(2).filter { it.second.isNotEmpty() }) {
            for (v in listOf(s to a, Names.bareName(s) to Names.primaryArtist(a))) {
                if (v.first.isNotEmpty() && exact.none { Names.key(it.first) == Names.key(v.first) && Names.key(it.second) == Names.key(v.second) }) exact += v
            }
        }
        val urls = ArrayList<Pair<String, Boolean>>()
        for ((s, a) in exact.take(3)) {
            var u = "https://lrclib.net/api/get?track_name=${Web.enc(s)}&artist_name=${Web.enc(a)}"
            if (dur in 1f..3600f) u += "&duration=${dur.roundToInt()}"
            urls += u to false
        }
        // Then searches: forgiving about the song's name, and only the first artist.
        val firstSearch = urls.size
        for ((s, a0) in pairs) {
            val a = Names.primaryArtist(a0)
            var u = "https://lrclib.net/api/search?track_name=${Web.enc(s)}"
            if (a.isNotEmpty()) u += "&artist_name=${Web.enc(a)}"
            urls += u to true
        }
        // A slowed or sped-up song: the original too, to stretch its timing.
        if (speed != null) {
            val base = Names.withoutVersion(song)
            if (base.isNotEmpty() && base != song) {
                val a = Names.primaryArtist(artist)
                urls.add(minOf(firstSearch + 1, urls.size), "https://lrclib.net/api/search?track_name=${Web.enc(base)}&artist_name=${Web.enc(a)}" to true)
            }
        }

        val entries = ArrayList<Entry>()
        var exactPlain: String? = null
        var error: IOException? = null
        for ((k, pair) in urls.take(MAX_REQUESTS).withIndex()) {
            val (url, list) = pair
            // LRCLIB asks for a short pause between requests.
            if (k > 0) pause(250)
            val got = try {
                ask(url, get)
            } catch (e: IOException) {
                // Still busy or offline after several tries: stop for now.
                error = e
                break
            }
            if (got is Got.Body) {
                if (list) {
                    J.arr(runCatching { Web.json(got.text) }.getOrNull())?.mapNotNullTo(entries, ::entry)
                } else {
                    val e = runCatching { entry(Web.json(got.text)) }.getOrNull()
                    if (e != null && !e.instrumental && !(q.avoid != null && q.avoid in e.wordsKeys())) {
                        val s = e.syncedText()
                        if (s != null && e.off(dur) <= MAX_DRIFT) return Lyrics.parseLrc(s)
                        exactPlain = exactPlain ?: e.plainText()
                        entries += e
                    }
                }
            }
            val pick = choose(entries, pairs, dur, null, q.avoid)
            if (pick is Pick.Synced) return Lyrics.parseLrc(pick.text)
        }
        val found = when (val pick = choose(entries, pairs, dur, speed, q.avoid)) {
            is Pick.Synced -> Lyrics.parseLrc(pick.text)
            is Pick.Stretched -> Lyrics.parseLrc(pick.text).let { l -> l.copy(lines = l.lines.map { Line(it.at * pick.ratio, it.words) }) }
            // Plain lyrics: the exact match's first.
            is Pick.Plain -> Lyrics.plain(exactPlain ?: pick.text)
            null -> exactPlain?.let { Lyrics.plain(it) }
        }
        if (found != null && found.lines.isNotEmpty()) return found
        error?.let { throw it }
        return null
    }
}
