package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.lyrics.Engine
import io.github.ferasbusiness666.segno.lyrics.Query
import io.github.ferasbusiness666.segno.lyrics.Web
import io.github.ferasbusiness666.segno.lyrics.J
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The shared list of hard songs (tricky-songs.json, the same file desktop uses), asked for real
 * through the Kotlin engine. Needs the internet, so it only runs in the "Lyrics live check"
 * workflow (SEGNO_LIVE=1); it writes what each song got to build/lyrics-live.txt.
 */
class LyricsLiveTest {
    @Test
    fun liveTrickySongs() {
        assumeTrue("Only in the Lyrics live check workflow", System.getenv("SEGNO_LIVE") == "1")
        val cases = J.arr(Web.json(javaClass.classLoader!!.getResource("tricky-songs.json")!!.readText()))!!
        val out = StringBuilder()
        val wrong = ArrayList<String>()
        for (c in cases) {
            val title = J.str(J.get(c, "title")).orEmpty()
            val artist = J.str(J.get(c, "artist")).orEmpty()
            val q = Query(
                id = title,
                title = title,
                artist = artist,
                fromVideo = J.get(c, "from_video") == true,
                video = J.str(J.get(c, "video")),
                duration = J.num(J.get(c, "duration"))?.toFloat() ?: 0f,
            )
            val start = System.nanoTime()
            val (found, heard) = Engine.askAll(q)
            val secs = (System.nanoTime() - start) / 1e9
            val got = found?.let { (src, l) ->
                val first = l.lines.firstOrNull { it.words.isNotEmpty() }?.let { String.format(Locale.US, "%.2fs \"%s\"", it.at, it.words) }.orEmpty()
                "${if (l.synced) "timed" else "plain"} from $src, ${l.lines.size} lines, first $first"
            } ?: "nothing"
            out.append("$artist – $title\n    $got  (${String.format(Locale.US, "%.1f", secs)}s, $heard)\n")
            val expect = J.str(J.get(c, "expect")).orEmpty()
            val ok = when (expect) {
                "timed" -> found?.second?.synced == true
                "words" -> found != null
                "none" -> found == null
                else -> true
            }
            if (!ok) wrong += "$artist – $title: wanted $expect, got $got"
        }
        println(out)
        File("build").mkdirs()
        File("build/lyrics-live.txt").writeText(out.toString() + if (wrong.isEmpty()) "\nAll as expected.\n" else "\nNot as expected:\n" + wrong.joinToString("\n") + "\n")
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}
