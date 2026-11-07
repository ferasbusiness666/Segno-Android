package io.github.ferasbusiness666.segno.lyrics

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Lyrics kept on the phone, in order of trust: a `.lrc` file next to the song, then what was
 * found before (with the user's Earlier/Later fixes), then the places online. Ported from
 * desktop (saved, from_site, keep_timing, forget and tidy in src/lyrics.rs). Blocking.
 */
class Store(private val dir: File, private val askAll: (Query) -> Pair<Pair<Source, Lyrics>?, Heard> = { Engine.askAll(it) }) {
    companion object {
        /** How long "nothing found" is remembered before asking again. */
        const val MISS_DAYS = 7L
        /** Saved lyrics not touched for this long are deleted. */
        const val KEEP_DAYS = 120L
        /** Written into "not found" marks; raised when the matching gets better. */
        const val MATCHER = "2"
        private const val DAY_MS = 86_400_000L

        /** The same file names as desktop: the cleaned names and the length, hashed. */
        fun cacheName(title: String, artist: String, dur: Float): String {
            val id = "${Names.key(Names.cleanTitle(title))}\n${Names.key(Names.cleanArtist(artist))}\n${dur.roundToLong()}"
            return java.lang.Long.toHexString(Lyrics.fnv64(id.toByteArray())).padStart(16, '0')
        }
    }

    private class Files(val timed: File, val plain: File, val miss: File)

    private fun files(q: Query): Files {
        dir.mkdirs()
        val name = cacheName(q.title, q.artist, q.duration)
        return Files(File(dir, "$name.lrc"), File(dir, "$name.txt"), File(dir, "$name.none"))
    }

    private fun kept(l: Lyrics): Lyrics? = l.takeIf { it.lines.isNotEmpty() }

    private fun youngerThan(f: File, days: Long): Boolean {
        val m = f.lastModified()
        return m > 0 && System.currentTimeMillis() - m < days * DAY_MS
    }

    /** The `.lrc` next to a song, if there is one and Segno may read it. */
    fun sidecar(songPath: String?): Lyrics? {
        val path = songPath?.takeIf { it.isNotEmpty() } ?: return null
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        val lrc = File((if (dot > slash) path.substring(0, dot) else path) + ".lrc")
        val text = runCatching { if (lrc.isFile && lrc.length() < 512 * 1024) lrc.readText() else null }.getOrNull() ?: return null
        return kept(Lyrics.parseLrc(text))
    }

    /** What's known without asking: a `.lrc` beside the song, lyrics saved earlier, or a recent "not found". */
    fun saved(q: Query): LyricsState? {
        sidecar(q.songPath)?.let { return LyricsState.Found(it) }
        val f = files(q)
        val found = runCatching { if (f.timed.isFile) kept(Lyrics.parseLrc(f.timed.readText())) else null }.getOrNull()
            ?: runCatching { if (f.plain.isFile) kept(Lyrics.plain(f.plain.readText())) else null }.getOrNull()
        if (found != null) {
            f.timed.takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
            return LyricsState.Found(found)
        }
        val missed = youngerThan(f.miss, MISS_DAYS) && runCatching { f.miss.readText().trim() == MATCHER }.getOrDefault(false)
        return if (!q.force && missed) LyricsState.Missing else null
    }

    /** Keep lyrics whose timing was moved ("Earlier" / "Later"). */
    fun keepTiming(q: Query, l: Lyrics) {
        if (!l.synced) return
        runCatching { files(q).timed.writeText(l.toLrc()) }
    }

    /** Drop what's saved for a song ("Wrong lyrics?"). */
    fun forget(q: Query) {
        val f = files(q)
        for (p in listOf(f.timed, f.plain, f.miss)) p.delete()
    }

    /**
     * Ask every place and keep the answer. "Not found" is only remembered on the [lastTry], and
     * only when every place answered.
     */
    fun fromSite(q: Query, lastTry: Boolean): LyricsState {
        val f = files(q)
        val (found, heard) = askAll(q)
        if (found != null) {
            val l = found.second
            runCatching { if (l.synced) f.timed.writeText(l.toLrc()) else f.plain.writeText(l.text()) }
            f.miss.delete()
            return LyricsState.Found(l)
        }
        // Nobody answered, or LRCLIB was busy: not "not found", so it's asked again.
        if (heard.answered == 0 || heard.busy) return LyricsState.Failed(heard.busy)
        if (lastTry && heard.complete) runCatching { f.miss.writeText(MATCHER) }
        return LyricsState.Missing
    }

    /** One step of looking a song up. */
    sealed interface Step {
        data class Done(val state: LyricsState) : Step
        /** Not found, but worth asking once more later. */
        data object NotYet : Step
    }

    fun lookup(q: Query, lastTry0: Boolean): Step {
        if (q.avoid != null) forget(q)
        saved(q)?.let { return Step.Done(it) }
        val lastTry = (lastTry0 || q.force) && !q.ahead
        val state = fromSite(q, lastTry)
        return if (state == LyricsState.Missing && !lastTry) Step.NotYet else Step.Done(state)
    }

    /** Delete saved lyrics nobody has needed for months. */
    fun tidy() {
        dir.listFiles()?.forEach { if (!youngerThan(it, KEEP_DAYS)) it.delete() }
    }
}

/**
 * The lyrics worker: songs are looked up one at a time on one thread, and only the newest waiting
 * one counts, so skipping through songs doesn't flood the places. A song not found is asked about
 * once more [followUpMs] later (LRCLIB goes looking in the background), and only then remembered
 * as missing. Songs to read ahead wait until there's nothing else to do.
 */
class Worker(
    private val look: (Query, Boolean) -> Store.Step,
    private val reply: (String, LyricsState) -> Unit,
    private val followUpMs: Long = 15_000,
    before: () -> Unit = {},
) {
    private val queue = LinkedBlockingQueue<Query>()

    init {
        Thread({
            runCatching(before)
            run()
        }, "lyrics").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun send(q: Query) {
        queue.put(q)
    }

    private fun run() {
        var again: Pair<Query, Long>? = null
        var ahead: Query? = null
        while (true) {
            // Wait for something to do: not at all when there's a song to read ahead.
            val first: Query? = when {
                ahead != null -> queue.poll()
                again != null -> queue.poll(maxOf(0L, again.second - System.nanoTime()), TimeUnit.NANOSECONDS)
                else -> queue.take()
            }
            var now: Query? = null
            var q = first
            while (q != null) {
                if (q.ahead) ahead = q else now = q
                q = queue.poll()
            }
            val a = again
            if (now != null) {
                again = null
                when (val step = safe(now, false)) {
                    is Store.Step.Done -> reply(now.id, step.state)
                    Store.Step.NotYet -> again = now to System.nanoTime() + followUpMs * 1_000_000
                }
            } else if (a != null && a.second - System.nanoTime() <= 0) {
                again = null
                val step = safe(a.first, true)
                if (step is Store.Step.Done) reply(a.first.id, step.state)
            } else if (ahead != null) {
                val q2 = ahead
                ahead = null
                safe(q2, false)
            }
        }
    }

    private fun safe(q: Query, lastTry: Boolean): Store.Step = try {
        look(q, lastTry)
    } catch (e: Throwable) {
        Store.Step.Done(LyricsState.Failed(false))
    }
}
