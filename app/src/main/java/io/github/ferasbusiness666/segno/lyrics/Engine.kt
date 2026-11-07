package io.github.ferasbusiness666.segno.lyrics

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.abs

/** A song to find lyrics for. */
data class Query(
    /** Segno's track id ("yt:…", "sp:…", "local:…"), to tell answers apart. */
    val id: String,
    /** The song file on the phone, for a `.lrc` beside it (null for songs playing online). */
    val songPath: String? = null,
    /** For a song from a video: the video's own title and channel. Otherwise the song's tags. */
    val title: String,
    val artist: String,
    val fromVideo: Boolean = false,
    /** The YouTube video the audio is from, when known: YouTube Music knows which song it is. */
    val video: String? = null,
    /** Seconds; 0 when unknown. */
    val duration: Float = 0f,
    /** Ask again even if it wasn't found last time ("Try again"). */
    val force: Boolean = false,
    /** "Wrong lyrics?": forget what was found (its words' key) and look for something else. */
    val avoid: Long? = null,
    /** Read ahead for a song coming up: quietly, and a song not found isn't remembered as missing. */
    val ahead: Boolean = false,
)

/** What's known about a song's lyrics. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    /** No lyrics for this song. */
    data object Missing : LyricsState
    /** Couldn't ask. [busy]: LRCLIB said so (it often does); otherwise no connection. Asked again. */
    data class Failed(val busy: Boolean) : LyricsState
}

/** The places asked, most trusted first. */
enum class Source { YtMusic, Lrclib, NetEase }

/** What one place said. */
sealed interface Said {
    data class Found(val lyrics: Lyrics) : Said
    data object Nothing : Said
    /** Couldn't ask it. [busy]: it said so (LRCLIB often does). */
    data class Failed(val busy: Boolean) : Said
}

/** How a lookup went, besides what was found. */
data class Heard(
    /** Places that answered (found something or not). */
    val answered: Int,
    /** Every place answered, apart from ones left alone for not answering lately. */
    val complete: Boolean,
    /** LRCLIB said it's busy. */
    val busy: Boolean,
)

/**
 * Asking every lyrics place at once and keeping the most trusted answer. Ported from desktop
 * (ask_all, settle, from_ytmusic and from_netease in src/lyrics.rs). Blocking: runs on the
 * lyrics worker's thread, each place on its own thread.
 */
object Engine {
    private const val TAG = "Segno"
    val SOURCES = Source.values().toList()
    /** A place that didn't answer, while another did, is left alone this long. */
    const val REST_MS = 30 * 60 * 1000L
    /** Once timed lyrics are in hand, a more trusted place still asking gets this much longer. */
    const val BETTER_WAIT_MS = 8_000L
    /** Most time one song's lookup takes; then the best so far. */
    const val ALL_WAIT_MS = 30_000L
    /** A YouTube Music song this much longer or shorter isn't the same cut: its timing won't fit. */
    const val YTM_DRIFT = 2f

    /** Until when each place rests (System.nanoTime), 0: not resting. */
    private val resting = AtomicLongArray(3)

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "lyrics-source").apply { isDaemon = true } }

    /** How much lyrics from the place at [i] are trusted: lower is better. Timed beat plain. */
    fun rank(i: Int, l: Lyrics): Int = if (l.synced) i else SOURCES.size + i

    /**
     * Which answer to keep, once nothing still being asked could beat it. Null: keep waiting.
     * [Pick.None]: nothing was found. [asking]: the places asked.
     */
    fun settle(said: List<Said?>, asking: List<Boolean>, avoid: Long?): Pick? {
        val best = said.withIndex().mapNotNull { (i, s) ->
            val l = (s as? Said.Found)?.lyrics ?: return@mapNotNull null
            if (l.lines.isEmpty() || avoid == l.wordsKey()) null else rank(i, l) to i
        }.minWithOrNull(compareBy({ it.first }, { it.second }))
        // The best a place still being asked could do: timed lyrics.
        val waiting = said.indices.firstOrNull { asking[it] && said[it] == null }
        return when {
            waiting == null -> if (best == null) Pick.None else Pick.Of(best.second)
            best != null && best.first < waiting -> Pick.Of(best.second)
            else -> null
        }
    }

    sealed interface Pick {
        data object None : Pick
        data class Of(val index: Int) : Pick
    }

    fun isResting(i: Int): Boolean {
        val until = resting.get(i)
        return until != 0L && System.nanoTime() - until < 0
    }

    /** Ask every place that isn't resting, each on its own thread, and keep the most trusted answer. */
    fun askAll(q: Query, ask: (Source, Query) -> Said = ::askSource): Pair<Pair<Source, Lyrics>?, Heard> {
        val results = LinkedBlockingQueue<Pair<Int, Said>>()
        val asking = MutableList(3) { false }
        for ((i, src) in SOURCES.withIndex()) {
            if (isResting(i)) continue
            asking[i] = true
            try {
                pool.execute {
                    val said = try {
                        ask(src, q)
                    } catch (e: Throwable) {
                        Log.w(TAG, "lyrics: $src broke", e)
                        Said.Failed(false)
                    }
                    results.put(i to said)
                }
            } catch (e: Exception) {
                asking[i] = false
            }
        }
        val said = MutableList<Said?>(3) { null }
        val start = System.nanoTime()
        var deadline = start + ALL_WAIT_MS * 1_000_000
        var pick: Pick?
        while (true) {
            pick = settle(said, asking, q.avoid)
            if (pick != null) break
            val left = deadline - System.nanoTime()
            val got = if (left > 0) results.poll(left, TimeUnit.NANOSECONDS) else null
            if (got == null) {
                // Out of time: the best so far.
                val done = List(3) { asking[it] && said[it] != null }
                pick = settle(said, done, q.avoid)
                break
            }
            val (i, s) = got
            if (s is Said.Found && s.lyrics.synced) deadline = minOf(deadline, System.nanoTime() + BETTER_WAIT_MS * 1_000_000)
            said[i] = s
        }
        val answered = said.count { it is Said.Found || it == Said.Nothing }
        val heard = Heard(
            answered = answered,
            complete = (0 until 3).all { !asking[it] || said[it] is Said.Found || said[it] == Said.Nothing },
            busy = said[1] == Said.Failed(true),
        )
        // A place that failed while another answered is the problem, not the connection: leave it
        // alone for a while. A busy LRCLIB gets asked again (it usually clears up quickly).
        if (answered > 0) {
            for ((i, s) in said.withIndex()) {
                if (s == Said.Failed(false)) {
                    Log.w(TAG, "lyrics: ${SOURCES[i]} didn't answer; leaving it alone for a while")
                    resting.set(i, System.nanoTime() + REST_MS * 1_000_000)
                }
            }
        }
        val found = (pick as? Pick.Of)?.let { p -> (said[p.index] as? Said.Found)?.let { SOURCES[p.index] to it.lyrics } }
        return found to heard
    }

    /** For tests: no place is resting. */
    internal fun wakeAll() {
        for (i in 0 until 3) resting.set(i, 0)
    }

    fun askSource(src: Source, q: Query): Said {
        val got: Lyrics? = try {
            when (src) {
                Source.YtMusic -> fromYtMusic(q)
                Source.Lrclib -> Lrclib.find(q)
                Source.NetEase -> fromNetEase(q)
            }
        } catch (e: Lrclib.Busy) {
            return Said.Failed(true)
        } catch (e: Exception) {
            Log.w(TAG, "lyrics: $src: ${e.message}")
            return Said.Failed(false)
        }
        return if (got != null && got.lines.isNotEmpty()) Said.Found(got) else Said.Nothing
    }

    /** Timed lines from YouTube Music, tidied like any `.lrc` (pauses kept, none at the edges). */
    fun fromLines(lines: List<Line>): Lyrics {
        val tidy = lines.map { l -> Line(l.at, if (l.words.all { it == '♪' || it == '♫' || it == '♬' || it.isWhitespace() }) "" else l.words) }
        return Lyrics.parseLrc(Lyrics(tidy, true).toLrc())
    }

    fun fromWords(w: YtMusic.Words, fits: Boolean): Lyrics = when (w) {
        is YtMusic.Words.Timed -> if (fits) fromLines(w.lines) else Lyrics.plain(w.lines.joinToString("\n") { it.words })
        is YtMusic.Words.Plain -> Lyrics.plain(w.text)
    }

    /**
     * YouTube Music. The video the song plays from, when known: if it's the official song, its own
     * lyrics. If it's a music video, its song (found by YouTube Music's clean names for it), whose
     * timing only counts when it's the same length. Without a video, the song found by name.
     */
    fun fromYtMusic(q: Query, ytm: YtmApi = YtmApi.Real): Lyrics? {
        val dur = q.duration
        fun fits(d: Float) = dur <= 0f || d <= 0f || abs(d - dur) <= YTM_DRIFT
        // A music video's own words, if its song has none: never timed.
        var videoWords: String? = null
        val (song, artist) = if (q.video != null) {
            val t = ytm.track(q.video)
            if (t.song) {
                val b = t.lyrics ?: return null
                return ytm.lyrics(b)?.let { fromWords(it, fits(t.duration)) }
            }
            videoWords = t.lyrics
            t.title to t.artist
        } else {
            Names.namePairs(q.title, q.artist, q.fromVideo).firstOrNull() ?: return null
        }
        val words = "$song ${Names.primaryArtist(artist)}".trim()
        val found = if (words.isEmpty()) emptyList() else ytm.searchSongs(words)
        val twin = found.firstOrNull { s -> Names.sameSong(s.title, song) && (artist.isEmpty() || Names.sameArtist(s.artist, artist)) && fits(s.duration) }
        if (twin != null) {
            val t = ytm.track(twin.id)
            val b = t.lyrics
            if (b != null) {
                val w = ytm.lyrics(b)
                if (w != null) return fromWords(w, fits(maxOf(t.duration, twin.duration)))
            }
        }
        return videoWords?.let { b -> ytm.lyrics(b)?.let { fromWords(it, false) } }
    }

    /** The YouTube Music calls [fromYtMusic] makes, so tests can answer them from saved files. */
    interface YtmApi {
        fun track(id: String): YtMusic.Track
        fun searchSongs(query: String): List<YtMusic.Song>
        fun lyrics(browseId: String): YtMusic.Words?

        object Real : YtmApi {
            override fun track(id: String) = YtMusic.track(id)
            override fun searchSongs(query: String) = YtMusic.searchSongs(query)
            override fun lyrics(browseId: String) = YtMusic.lyrics(browseId)
        }
    }

    /**
     * NetEase: the song by name and about the same length, and only its timed lyrics (the rest
     * of what it has, the other places have too).
     */
    fun fromNetEase(q: Query): Lyrics? {
        val (song, artist) = Names.namePairs(q.title, q.artist, q.fromVideo).firstOrNull() ?: return null
        val dur = q.duration
        val found = NetEase.search("$song ${Names.primaryArtist(artist)}".trim())
        val twin = found.firstOrNull { s ->
            Names.sameSong(s.title, song) && (artist.isEmpty() || Names.sameArtist(s.artist, artist)) && (dur <= 0f || abs(s.duration - dur) <= Lrclib.MAX_DRIFT)
        } ?: return null
        val lrc = NetEase.lyrics(twin.id) ?: return null
        return netEaseLyrics(lrc)
    }

    /** "纯音乐，请欣赏" (an instrumental) and other one-liners aren't lyrics. */
    fun netEaseLyrics(lrc: String): Lyrics? {
        val l = Lyrics.parseLrc(lrc)
        val lines = l.lines.count { it.words.isNotEmpty() }
        val timed = l.lines.any { it.at > 0f }
        return if (l.synced && timed && lines > 2) l else null
    }
}
