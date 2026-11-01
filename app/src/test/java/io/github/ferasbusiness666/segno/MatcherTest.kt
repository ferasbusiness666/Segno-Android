package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.spotify.Matcher
import io.github.ferasbusiness666.segno.spotify.Matcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    @Test
    fun lengthWindowIs15PercentPlus5Seconds() {
        val w = Matcher.window(200.0)
        assertEquals(165L, w.first) // 200 * 0.85 - 5
        assertEquals(235L, w.last) // 200 * 1.15 + 5
        assertEquals(0L, Matcher.window(3.0).first)
    }

    @Test
    fun otherVersionsAreLeftOut() {
        val re = Matcher.otherVersions("Blinding Lights")!!
        assertTrue(re.containsMatchIn("Blinding Lights (Live at the Grammys)"))
        assertTrue(re.containsMatchIn("blinding lights - SPED UP"))
        assertTrue(re.containsMatchIn("Blinding Lights 8D Audio"))
        assertFalse(re.containsMatchIn("The Weeknd - Blinding Lights (Official Audio)"))
        // "live" inside another word doesn't count.
        assertFalse(re.containsMatchIn("Delivered"))
        // A song that is a remix keeps remixes in.
        val remix = Matcher.otherVersions("Levitating (Remix)")!!
        assertFalse(remix.containsMatchIn("Levitating Remix feat. DaBaby"))
        assertTrue(remix.containsMatchIn("Levitating live"))
    }

    @Test
    fun picksTheFirstGoodMatch() {
        val results = listOf(
            Candidate("live000000a", "The Weeknd - Blinding Lights (Live)", 205),
            Candidate("long000000b", "Blinding Lights 10 hours", 36000),
            Candidate("good000000c", "The Weeknd - Blinding Lights (Official Audio)", 202),
            Candidate("good000000d", "Blinding Lights", 200),
        )
        assertEquals("good000000c", Matcher.pick(results, "Blinding Lights", 200_040)!!.videoId)
    }

    @Test
    fun fallsBackToTheFirstResult() {
        val results = listOf(
            Candidate("aaaaaaaaaaa", "Song (Live)", 900),
            Candidate("bbbbbbbbbbb", "Song cover", 30),
        )
        assertEquals("aaaaaaaaaaa", Matcher.pick(results, "Song", 200_000)!!.videoId)
        assertNull(Matcher.pick(emptyList(), "Song", 200_000))
    }

    @Test
    fun onlyLooksAtTheFirstFive() {
        val bad = (1..5).map { Candidate("bad$it", "Song live $it", 200) }
        val results = bad + Candidate("late0000000", "Song", 200)
        assertEquals("bad1", Matcher.pick(results, "Song", 200_000)!!.videoId)
    }

    @Test
    fun unknownLengthTakesTheFirst() {
        val results = listOf(Candidate("x1", "Song (Live)", 100), Candidate("x2", "Song", 200))
        assertEquals("x1", Matcher.pick(results, "Song", 0)!!.videoId)
    }
}
