package io.github.ferasbusiness666.segno.lyrics

import kotlin.math.roundToLong

/** One line: when it starts (seconds) and its words. Empty words are a pause. */
data class Line(val at: Float, val words: String)

/**
 * A song's lyrics. Ported from desktop Segno (src/lyrics.rs), so both apps read and pick lyrics
 * the same way. Plain Kotlin, so it's tested without a phone.
 */
data class Lyrics(
    /** Sorted by time. Plain lyrics have every line at 0, and an empty line between verses. */
    val lines: List<Line>,
    /** Timed to the song. */
    val synced: Boolean,
) {
    /** The line being sung at [t] seconds (null before the first one, and for plain lyrics). */
    fun lineAt(t: Float): Int? {
        if (!synced) return null
        var lo = 0
        var hi = lines.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].at <= t) lo = mid + 1 else hi = mid
        }
        return if (lo == 0) null else lo - 1
    }

    /** When the line after [idx] starts (null after the last one, and for plain lyrics). */
    fun nextStart(idx: Int?): Float? {
        if (!synced) return null
        return lines.getOrNull(if (idx == null) 0 else idx + 1)?.at
    }

    /** Back to LRC text, to keep on disk. `[re:Segno]` marks files Segno wrote. */
    fun toLrc(): String {
        val sb = StringBuilder(SEGNO_TAG).append('\n')
        for (l in lines) {
            val cs = (l.at * 100f).roundToLong().coerceAtLeast(0)
            sb.append('[')
                .append(two(cs / 6000)).append(':').append(two(cs % 6000 / 100)).append('.').append(two(cs % 100))
                .append(']').append(l.words).append('\n')
        }
        return sb.toString()
    }

    /** The same lyrics [by] seconds later (earlier when negative). Lines never go before 0. */
    fun shifted(by: Float): Lyrics = copy(lines = lines.map { Line((it.at + by).coerceAtLeast(0f), it.words) })

    /** Tells lyrics apart by their words alone (timed or not), for "Wrong lyrics?". */
    fun wordsKey(): Long = fnv64(lines.filter { it.words.isNotEmpty() }.joinToString("\n") { it.words.lowercase() }.toByteArray())

    /** The words alone, one line each (for saving plain lyrics). */
    fun text(): String = lines.joinToString("\n") { it.words }

    companion object {
        /** The first line of `.lrc` files Segno writes. */
        const val SEGNO_TAG = "[re:Segno]"

        private fun two(n: Long) = if (n < 10) "0$n" else n.toString()

        /** Plain lyrics: one line per line, a single gap between verses. */
        fun plain(text: String): Lyrics {
            val lines = ArrayList<Line>()
            for (raw in text.lines()) {
                val line = Names.words(raw).joinToString(" ")
                if (line.isNotEmpty() || lines.lastOrNull()?.words?.isNotEmpty() == true) lines += Line(0f, line)
            }
            while (lines.lastOrNull()?.words?.isEmpty() == true) lines.removeAt(lines.size - 1)
            return Lyrics(lines, synced = false)
        }

        /**
         * Parse LRC text: `[01:23.45] words`. Several stamps may share a line, tags such as
         * `[ar:…]` are skipped, `[offset:±ms]` is honoured and word stamps (`<00:01.20>`) dropped.
         */
        fun parseLrc(text: String): Lyrics {
            var offset = 0f
            val lines = ArrayList<Line>()
            for (raw in text.lines()) {
                var rest = raw.trimStart('﻿').trim()
                val stamps = ArrayList<Float>()
                while (rest.startsWith('[')) {
                    val r = rest.substring(1)
                    val end = r.indexOf(']')
                    if (end < 0) break
                    val tag = r.substring(0, end)
                    val t = stamp(tag)
                    if (t != null) {
                        stamps += t
                    } else if (tag.startsWith("offset:")) {
                        offset = (tag.removePrefix("offset:").trim().toFloatOrNull() ?: 0f) / 1000f
                    } else if (!tag.contains(':')) {
                        // "[Chorus]" and the like are part of the words.
                        break
                    }
                    rest = r.substring(end + 1).trimStart()
                }
                if (stamps.isEmpty()) continue
                val words = stripWordStamps(rest)
                for (s in stamps) lines += Line((s - offset).coerceAtLeast(0f), words)
            }
            lines.sortWith(compareBy { it.at })
            // A pause right at the start or end adds nothing.
            val first = lines.indexOfFirst { it.words.isNotEmpty() }.let { if (it < 0) lines.size else it }
            val kept = ArrayList(lines.subList(first, lines.size))
            while (kept.lastOrNull()?.words?.isEmpty() == true) kept.removeAt(kept.size - 1)
            return Lyrics(kept, synced = true)
        }

        internal fun stamp(tag: String): Float? {
            val colon = tag.indexOf(':')
            if (colon < 0) return null
            val m = tag.substring(0, colon)
            val s = tag.substring(colon + 1)
            fun digits(x: String) = x.isNotEmpty() && x.all { it in '0'..'9' }
            if (m.length > 3 || !digits(m)) return null
            val dot = s.indexOf('.')
            val whole = if (dot < 0) s else s.substring(0, dot)
            val frac = if (dot < 0) "0" else s.substring(dot + 1)
            if (!digits(whole) || !digits(frac)) return null
            val sec = s.toFloatOrNull() ?: return null
            return m.toFloat() * 60f + sec
        }

        private fun stripWordStamps(s: String): String {
            val out = StringBuilder(s.length)
            var rest = s
            while (true) {
                val i = rest.indexOf('<')
                if (i < 0) break
                val after = rest.substring(i + 1)
                val j = after.indexOf('>')
                if (j >= 0 && stamp(after.substring(0, j)) != null) {
                    out.append(rest, 0, i)
                    rest = after.substring(j + 1)
                } else {
                    out.append(rest, 0, i + 1)
                    rest = after
                }
            }
            out.append(rest)
            return Names.words(out.toString()).joinToString(" ")
        }

        /** FNV-1a, 64 bits: the same numbers as desktop's store::hash_bytes. */
        fun fnv64(bytes: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in bytes) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
