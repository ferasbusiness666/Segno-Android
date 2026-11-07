package io.github.ferasbusiness666.segno.lyrics

/**
 * Names: video titles are messy, the lyrics places want the plain song and artist. Ported from
 * desktop Segno (src/lyrics.rs and remove_junk in src/ytdlp.rs) so both apps match the same way.
 */
object Names {
    /** Speed edits: their lyrics are the original's, stretched. */
    enum class Speed { Slower, Faster }

    fun speedOf(title: String): Speed? {
        val t = title.lowercase()
        return when {
            t.contains("slowed") || t.contains("daycore") -> Speed.Slower
            t.contains("sped up") || t.contains("speed up") || t.contains("nightcore") -> Speed.Faster
            else -> null
        }
    }

    /** Words that mark a version of a song rather than its name. */
    fun isVersion(part: String): Boolean {
        val t = part.lowercase()
        return speedOf(t) != null || t.contains("reverb") || t.contains("remaster") || t.contains("bass boost") ||
            words(t).any { it == "8d" }
    }

    /** "01 Song", "1. Song", "03 - Song" (titles from file names) → "Song". "7 Rings" and "22" stay. */
    fun stripTrackNumber(title: String): String {
        val t = title.trimStart()
        val digits = t.takeWhile { it in '0'..'9' }.length
        if (digits == 0 || digits > 3) return t
        val rest = t.substring(digits)
        val after = when {
            digits == 2 && t.startsWith('0') && rest.startsWith(' ') -> rest
            rest.startsWith(". ") -> rest.substring(2)
            rest.startsWith(" - ") -> rest.substring(3)
            rest.startsWith('_') -> rest.substring(1)
            else -> return t
        }.trimStart(' ', '-', '.', '_')
        return if (after.isEmpty()) t else after
    }

    /** "Song (Official Video) [Remastered 2011]" → "Song". Speed edits stay: they have lyrics of their own. */
    fun cleanTitle(title: String): String =
        stripVersions(removeJunk(stripTrackNumber(title))) { it.lowercase().contains("remaster") }

    /** The song's name without any version marks: "addiction (Slowed + Reverb)" → "addiction". */
    fun withoutVersion(title: String): String = stripVersions(title, ::isVersion)

    /** Drop brackets and " - …" endings that [drop] picks out. */
    fun stripVersions(title: String, drop: (String) -> Boolean): String {
        val out = StringBuilder(title.length)
        var open: Char? = null
        var close = ')'
        val group = StringBuilder()
        for (ch in title) {
            val o = open
            when {
                o == null && (ch == '(' || ch == '[') -> {
                    open = ch
                    close = if (ch == '(') ')' else ']'
                }
                o == null -> out.append(ch)
                ch == close -> {
                    if (!drop(group.toString())) out.append(o).append(group).append(close)
                    group.setLength(0)
                    open = null
                }
                else -> group.append(ch)
            }
        }
        // A bracket that never closed.
        open?.let { if (!drop(group.toString())) out.append(it).append(group) }
        var s = words(out.toString()).joinToString(" ")
        val i = s.lastIndexOf(" - ")
        if (i >= 0 && drop(s.substring(i + 3))) s = s.substring(0, i)
        return s
    }

    /** "Artist - Topic", "ArtistVEVO" → the artist. */
    fun cleanArtist(artist: String): String {
        val a = artist.trim().removeSuffix(" - Topic")
        return if (a.length > 4 && a.regionMatches(a.length - 4, "vevo", 0, 4, ignoreCase = true)) a.substring(0, a.length - 4).trim() else a
    }

    /** What goes between two artists' names: "A x B", "A, B", "A feat. B", "A و B" (Arabic "and"). */
    private val ARTIST_SEPS = listOf(" x ", " × ", ", ", "; ", " & ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " vs. ", " / ", " و ")
    private val FEAT = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ")

    private fun firstAt(s: String, seps: List<String>): Int {
        for (i in s.indices) if (seps.any { s.regionMatches(i, it, 0, it.length, ignoreCase = true) }) return i
        return -1
    }

    /** The first of several artists ("MARWAN PABLO x LEGE-CY" → "MARWAN PABLO"). */
    fun primaryArtist(a: String): String {
        val cut = firstAt(a, ARTIST_SEPS)
        return (if (cut < 0) a else a.substring(0, cut)).trim()
    }

    /** "Song feat. X" → "Song". */
    fun withoutGuests(s: String): String {
        val cut = firstAt(s, FEAT)
        return (if (cut < 0) s else s.substring(0, cut)).trim()
    }

    private fun beforeBracket(s: String): String {
        val i = s.indexOfAny(charArrayOf('(', '['))
        return if (i < 0) s else s.substring(0, i)
    }

    private fun beforeDash(s: String): String {
        val i = s.indexOf(" - ")
        return if (i < 0) s else s.substring(0, i)
    }

    /** The bare name of a song: "Mamushi (feat. Yuki Chiba)" → "Mamushi", "Song - Radio Edit" → "Song". */
    fun bareName(song: String): String = withoutGuests(beforeDash(beforeBracket(song))).trim()

    /** Looks like a list of artists rather than a song's name. */
    fun looksLikeArtists(s: String): Boolean = firstAt(s, ARTIST_SEPS) >= 0

    /** Lowercase letters and digits only: "Lege-Cy" and "LEGE CY" are the same name. */
    fun key(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isAlphabetic(cp) || isNumeric(cp)) sb.append(String(Character.toChars(cp)).lowercase())
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isNumeric(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /** Two names for the same artist ("HatemBas" and "HATEM BAS", or one of several). */
    fun sameArtist(found: String, ours: String): Boolean {
        val f = key(found)
        val o = key(primaryArtist(ours))
        val f1 = key(primaryArtist(found))
        return o.isNotEmpty() && f1.isNotEmpty() && (f.contains(o) || o.contains(f1))
    }

    /** Two names for the same song, whatever the version or guests. */
    fun sameSong(found: String, ours: String): Boolean {
        fun base(s: String) = key(withoutGuests(beforeDash(beforeBracket(withoutVersion(s)))))
        val o = base(ours)
        return o.isNotEmpty() && base(found) == o
    }

    /** "Artist - Song" (or "Song - Artist"): the two sides. */
    fun splitDash(s: String): Pair<String, String>? {
        for (d in listOf(" - ", " – ", " — ")) {
            val i = s.indexOf(d)
            if (i >= 0) {
                val a = s.substring(0, i).trim()
                val b = s.substring(i + d.length).trim()
                return if (a.isNotEmpty() && b.isNotEmpty()) a to b else null
            }
        }
        return null
    }

    /**
     * The (song, artist) pairs worth asking about, best guess first. Video titles come in every
     * shape: "Artist - Song", "Song - Artist" (common in Arabic), "Artist x Artist - Song | the same
     * in Arabic". Each half of a "|" title is tried, the side with several names in it is taken as
     * the artist, and the other way round is tried after.
     */
    fun namePairs(rawTitle: String, rawArtist: String, fromVideo: Boolean): List<Pair<String, String>> {
        val title = cleanTitle(rawTitle).replace(" ｜ ", " | ")
        val artist = cleanArtist(rawArtist)
        val halves = title.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
        fun artistLike(s: String) = looksLikeArtists(s) || (artist.isNotEmpty() && key(s) == key(artist))
        val first = ArrayList<Pair<String, String>>()
        val then = ArrayList<Pair<String, String>>()
        for (h in halves) {
            val dash = splitDash(h)
            val (p, q) = when {
                // "Artist - Song" unless the right side is the one that looks like artists.
                dash != null -> {
                    val (l, r) = dash
                    if (artistLike(r) && !artistLike(l)) l to r else r to l
                }
                halves.size > 1 && artist.isNotEmpty() -> if (artistLike(h)) artist to h else h to artist
                else -> continue
            }
            first += p to q
            then += q to p
        }
        val given = title to artist
        // A video's channel is often not the artist, and a "|" title is never one song name; a
        // song's own tags are usually right.
        val trustGiven = !fromVideo && artist.isNotEmpty() && halves.size == 1 &&
            first.all { (s, a) -> key(s) != key(artist) && key(a) != key(artist) }
        val ordered = if (trustGiven) listOf(given) + first + then else first + listOf(given) + then
        val out = ArrayList<Pair<String, String>>()
        for ((s0, a) in ordered) {
            val s = s0.trim { it == '-' || it == '|' || it.isWhitespace() }
            if (s.isEmpty() || out.any { (s2, a2) -> key(s2) == key(s) && key(a2) == key(a) }) continue
            out += s to a
        }
        return out
    }

    // Title junk (ytdlp.rs remove_junk) ------------------------------------------------------------

    private val JUNK = listOf(
        "official", "video", "audio", "lyric", "visualizer", "visualiser", "hd", "4k", "hq", "mv", "m/v", "clip",
        "explicit", "music video", "full song", "فيديو", "كليب", "حصري", "اوديو", "أوديو", "الكلمات",
    )

    private fun isAscii(s: String) = s.all { it.code < 128 }

    /** Strip "(Official Video)", "[Lyrics]", "| Official Audio" and friends. */
    fun removeJunk(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val close = when (c) {
                '(' -> ')'
                '[' -> ']'
                '【' -> '】'
                else -> null
            }
            if (close != null) {
                val j = s.indexOf(close, i + 1)
                if (j >= 0) {
                    val low = s.substring(i + 1, j).lowercase()
                    val pieces = low.splitOn { ch -> !Character.isLetterOrDigit(ch) && ch != '/' }
                    val junk = JUNK.any { w ->
                        pieces.any { word -> word == w || (w.toByteArray().size >= 5 && word.startsWith(w)) } ||
                            (w.contains(' ') && low.contains(w)) ||
                            (!isAscii(w) && low.contains(w))
                    }
                    if (junk) {
                        i = j + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        var o = out.toString()
        // A trailing "| Official Video" style segment.
        val bar = o.lastIndexOf(" | ")
        if (bar >= 0) {
            val tail = o.substring(bar + 3).lowercase()
            if (JUNK.any { tail.contains(it) }) o = o.substring(0, bar)
        }
        return words(o).joinToString(" ").trim { it == '-' || it == '|' || it == '–' || it.isWhitespace() }
    }

    // Versions (ytdlp.rs version_kinds) -------------------------------------------------------------

    private val VERSION_KINDS = listOf("live", "cover", "karaoke", "instrumental", "nightcore", "sped up", "slowed", "reverb", "8d", "remix", "acoustic")

    /** Which other-version words (live, remix, sped up…) a title has, as whole words. */
    fun versionKinds(title: String): List<String> {
        val t = title.lowercase()
        return VERSION_KINDS.filter { k ->
            var at = t.indexOf(k)
            var hit = false
            while (at >= 0 && !hit) {
                val before = at == 0 || !t[at - 1].isLetterOrDigit()
                val end = at + k.length
                val after = end >= t.length || !t[end].isLetterOrDigit()
                hit = before && after
                at = t.indexOf(k, at + 1)
            }
            hit
        }
    }

    private fun String.splitOn(isSep: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in this) {
            if (isSep(ch)) {
                out += cur.toString()
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    /** Words split on any whitespace (Rust's split_whitespace). */
    fun words(s: String): List<String> = s.splitOn { it.isWhitespace() || Character.isSpaceChar(it) }.filter { it.isNotEmpty() }
}
