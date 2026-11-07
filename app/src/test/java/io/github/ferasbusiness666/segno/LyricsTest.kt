package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.lyrics.Engine
import io.github.ferasbusiness666.segno.lyrics.Heard
import io.github.ferasbusiness666.segno.lyrics.Line
import io.github.ferasbusiness666.segno.lyrics.Lrclib
import io.github.ferasbusiness666.segno.lyrics.Lrclib.Entry
import io.github.ferasbusiness666.segno.lyrics.Lrclib.Pick
import io.github.ferasbusiness666.segno.lyrics.Lyrics
import io.github.ferasbusiness666.segno.lyrics.LyricsState
import io.github.ferasbusiness666.segno.lyrics.Names
import io.github.ferasbusiness666.segno.lyrics.Names.Speed
import io.github.ferasbusiness666.segno.lyrics.Query
import io.github.ferasbusiness666.segno.lyrics.Said
import io.github.ferasbusiness666.segno.lyrics.Source
import io.github.ferasbusiness666.segno.lyrics.Store
import io.github.ferasbusiness666.segno.lyrics.Web
import io.github.ferasbusiness666.segno.lyrics.Worker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Ported from desktop Segno's tests in src/lyrics.rs, so both apps behave the same. */
class LyricsTest {
    private fun l(at: Float, w: String) = Line(at, w)

    @Test
    fun parsesStampsTagsAndRepeats() {
        val x = Lyrics.parseLrc("﻿[ar:Someone]\n[ti:Song]\n[00:01.50]Hello\n[00:10.00][00:30.25]Chorus line\n[01:05]Last\n[01:09.123]\n")
        assertTrue(x.synced)
        assertEquals(listOf(l(1.5f, "Hello"), l(10f, "Chorus line"), l(30.25f, "Chorus line"), l(65f, "Last")), x.lines)
    }

    @Test
    fun keepsPausesButNotAtTheEdges() {
        val x = Lyrics.parseLrc("[00:00.00]\n[00:05.00]One\n[00:09.00]\n[00:12.00]Two\n[00:20.00]\n")
        assertEquals(listOf(l(5f, "One"), l(9f, ""), l(12f, "Two")), x.lines)
    }

    @Test
    fun offsetAndWordStamps() {
        assertEquals(listOf(l(1.5f, "Hello <3 you")), Lyrics.parseLrc("[offset:500]\n[00:02.00]<00:02.00>Hel<00:02.40>lo <3 you\n").lines)
    }

    @Test
    fun notLyrics() {
        assertTrue(Lyrics.parseLrc("Just some words\nwith no times").lines.isEmpty())
        assertTrue(Lyrics.parseLrc("[verse]\nwords").lines.isEmpty())
        assertTrue(Lyrics.parseLrc("[00:1.2.3]bad\n[:10]bad\n[00:.5]bad").lines.isEmpty())
        assertEquals(listOf(l(4f, "[Chorus] la")), Lyrics.parseLrc("[00:04.00][Chorus] la").lines)
    }

    @Test
    fun plainLyrics() {
        val x = Lyrics.plain("\n\nFirst  line\nSecond\n\n\n\nThird\n\n")
        assertFalse(x.synced)
        assertEquals(listOf("First line", "Second", "", "Third"), x.lines.map { it.words })
        assertNull(x.lineAt(50f))
        assertNull(x.nextStart(null))
    }

    @Test
    fun savesAsLrcAndBack() {
        val x = Lyrics(listOf(l(59.996f, "a"), l(61.5f, "b"), l(600f, ""), l(601f, "c")), true)
        assertEquals("[re:Segno]\n[01:00.00]a\n[01:01.50]b\n[10:00.00]\n[10:01.00]c\n", x.toLrc())
        assertEquals(4, Lyrics.parseLrc(x.toLrc()).lines.size)
    }

    @Test
    fun findsTheLineBeingSung() {
        val x = Lyrics.parseLrc("[00:05.00]a\n[00:10.00]b\n[00:15.00]c")
        assertNull(x.lineAt(0f))
        assertEquals(0, x.lineAt(5f))
        assertEquals(1, x.lineAt(12f))
        assertEquals(2, x.lineAt(99f))
        assertEquals(5f, x.nextStart(null))
        assertEquals(15f, x.nextStart(1))
        assertNull(x.nextStart(2))
    }

    @Test
    fun cleansNames() {
        assertEquals("Blinding Lights", Names.cleanTitle("Blinding Lights (Official Video) [HD]"))
        assertEquals("Song (feat. Someone)", Names.cleanTitle("Song (feat. Someone)"))
        assertEquals("Total Eclipse (Eclipse Remix)", Names.cleanTitle("Total Eclipse (Eclipse Remix)"))
        assertEquals("Song", Names.cleanTitle("Song - 2011 Remaster"))
        assertEquals("addiction (Slowed)", Names.cleanTitle("addiction (Slowed)"))
        assertEquals("addiction", Names.withoutVersion("addiction (Slowed + Reverb)"))
        assertEquals("addiction", Names.withoutVersion("addiction - Sped Up"))
        assertEquals("The Weeknd", Names.cleanArtist("The Weeknd - Topic"))
        assertEquals("Adele", Names.cleanArtist("AdeleVEVO"))
        assertEquals("VEVO", Names.cleanArtist("VEVO"))
        assertEquals("MARWAN PABLO", Names.primaryArtist("MARWAN PABLO x LEGE-CY x HATEM BAS"))
        assertEquals("مروان بابلو", Names.primaryArtist("مروان بابلو و ليجي-سي"))
        assertEquals("Calvin Harris", Names.primaryArtist("Calvin Harris, Dua Lipa"))
        assertEquals("Xavier", Names.primaryArtist("Xavier"))
        assertEquals(Speed.Slower, Names.speedOf("Song (Slowed + Reverb)"))
        assertEquals(Speed.Faster, Names.speedOf("Song - sped up"))
        assertNull(Names.speedOf("Song (Live)"))
    }

    @Test
    fun matchesNamesLoosely() {
        assertTrue(Names.sameArtist("Marwan Pablo, Lege-Cy, HatemBas", "MARWAN PABLO x LEGE-CY x HATEM BAS"))
        assertTrue(Names.sameArtist("Simon & Garfunkel", "Simon & Garfunkel"))
        assertFalse(Names.sameArtist("Kanye West", "LONOWN"))
        assertTrue(Names.sameSong("addiction - Slowed", "addiction (Slowed)"))
        assertTrue(Names.sameSong("Shoft Kalam", "SHOFT KALAM"))
        assertTrue(Names.sameSong("Song (feat. X)", "Song"))
        assertFalse(Names.sameSong("Addictions", "Addiction"))
    }

    @Test
    fun videoTitlesBecomeSongAndArtist() {
        var pairs = Names.namePairs("SHOFT KALAM - MARWAN PABLO x LEGE-CY x HATEM BAS | شفت كلام - مروان بابلو و ليجي-سي و حاتم بس", "Marwan Pablo", true)
        assertEquals("SHOFT KALAM" to "MARWAN PABLO x LEGE-CY x HATEM BAS", pairs[0])
        assertEquals("شفت كلام" to "مروان بابلو و ليجي-سي و حاتم بس", pairs[1])
        pairs = Names.namePairs("MARWAN PABLO x LEGE-CY x HATEM BAS | شفت كلام - مروان بابلو و ليجي-سي و حاتم بس", "SHOFT KALAM", false)
        assertEquals("SHOFT KALAM" to "MARWAN PABLO x LEGE-CY x HATEM BAS", pairs[0])
        pairs = Names.namePairs("LONOWN - addiction (Official Video)", "Trap Nation", true)
        assertEquals("addiction" to "LONOWN", pairs[0])
        pairs = Names.namePairs("Mr. Brightside - Live", "The Killers", false)
        assertEquals("Mr. Brightside - Live" to "The Killers", pairs[0])
        assertEquals(listOf("Shape of You" to "Ed Sheeran"), Names.namePairs("Shape of You", "Ed Sheeran", false))
    }

    private fun entry(track: String, artist: String, dur: Double, synced: String?, plain: String?) = Entry(track, artist, dur, false, synced, plain)

    @Test
    fun picksTheRightLyrics() {
        var pairs = listOf("Shoft Kalam" to "Marwan Pablo")
        val list = listOf(
            entry("Shoft Kalam", "Marwan Pablo", 210.0, null, "plain"),
            entry("Shoft Kalam", "Marwan Pablo, Lege-Cy, HatemBas", 209.0, "[00:01.00]timed", null),
            entry("Shoft Kalam", "Someone Else", 210.0, "[00:01.00]wrong artist", null),
        )
        assertEquals(Pick.Synced("[00:01.00]timed"), Lrclib.choose(list, pairs, 210f, null, null))
        // Another cut of the song: timing would drift, so plain lyrics instead.
        assertEquals(Pick.Plain("plain"), Lrclib.choose(list, pairs, 260f, null, null))
        // A different song with the same name doesn't count.
        val other = listOf(entry("Addiction", "Kanye West", 264.0, "[00:01.00]no", "no"))
        pairs = listOf("addiction" to "LONOWN")
        assertNull(Lrclib.choose(other, pairs, 264f, null, null))
    }

    @Test
    fun wrongLyricsAreSkipped() {
        val pairs = listOf("Song" to "Artist")
        val list = listOf(entry("Song", "Artist", 200.0, "[00:01.00]Wrong words", null), entry("Song", "Artist", 201.0, "[00:01.00]Right words", null))
        val wrong = Lyrics.parseLrc("[00:05.00]wrong WORDS").wordsKey()
        assertEquals(Pick.Synced("[00:01.00]Wrong words"), Lrclib.choose(list, pairs, 200f, null, null))
        assertEquals(Pick.Synced("[00:01.00]Right words"), Lrclib.choose(list, pairs, 200f, null, wrong))
    }

    @Test
    fun stretchesTheOriginalForASlowedVersion() {
        val pairs = listOf("addiction (Slowed)" to "LONOWN")
        val list = listOf(
            entry("addiction", "LONOWN", 209.0, "[00:10.00]a", null),
            entry("addiction", "LONOWN", 209.0, "[00:10.00]a", null),
            entry("addiction", "LONOWN", 355.0, "[00:10.00]extended", null),
        )
        val p = Lrclib.choose(list, pairs, 251f, Speed.Slower, null) as Pick.Stretched
        assertEquals("[00:10.00]a", p.text)
        assertEquals(251f / 209f, p.ratio, 1e-4f)
        // Timed lyrics made for the slowed version itself come first.
        val withOwn = list + entry("addiction - Slowed", "LONOWN", 250.0, "[00:12.00]own", null)
        assertEquals(Pick.Synced("[00:12.00]own"), Lrclib.choose(withOwn, pairs, 251f, Speed.Slower, null))
    }

    @Test
    fun trackNumbersFromFileNames() {
        assertEquals("Coastline", Names.stripTrackNumber("01 Coastline"))
        assertEquals("Song", Names.stripTrackNumber("1. Song"))
        assertEquals("Song", Names.stripTrackNumber("03 - Song"))
        assertEquals("Song", Names.stripTrackNumber("12_Song"))
        assertEquals("7 Rings", Names.stripTrackNumber("7 Rings"))
        assertEquals("22", Names.stripTrackNumber("22"))
        assertEquals("1979", Names.stripTrackNumber("1979"))
        assertEquals("99 Problems", Names.stripTrackNumber("99 Problems"))
        assertEquals("Mamushi", Names.bareName("Mamushi (feat. Yuki Chiba)"))
        assertEquals("Song", Names.bareName("Song - Radio Edit"))
        assertEquals("Song", Names.bareName("Song feat. X"))
    }

    @Test
    fun encodesForUrls() {
        assertEquals("Beyonc%C3%A9%20%26%20Jay-Z", Web.enc("Beyoncé & Jay-Z"))
    }

    @Test
    fun cacheNamesIgnoreVideoTalk() {
        assertEquals(Store.cacheName("song", "artist", 199.8f), Store.cacheName("Song (Official Video)", "ArtistVEVO", 200.2f))
        assertNotEquals(Store.cacheName("Song", "Artist", 230f), Store.cacheName("Song", "Artist", 200f))
        // The same numbers as desktop's FNV-1a.
        assertEquals("af63dc4c8601ec8c", java.lang.Long.toHexString(Lyrics.fnv64("a".toByteArray())))
    }

    @Test
    fun timingMovesAndStaysInOrder() {
        val x = Lyrics.parseLrc("[00:00.30]a\n[00:01.00]b\n[00:05.00]c")
        val later = x.shifted(0.5f).lines.map { it.at }
        listOf(0.8f, 1.5f, 5.5f).forEachIndexed { i, v -> assertEquals(v, later[i], 1e-4f) }
        val earlier = x.shifted(-0.5f)
        listOf(0f, 0.5f, 4.5f).forEachIndexed { i, v -> assertEquals(v, earlier.lines[i].at, 1e-4f) }
        assertTrue(earlier.synced)
    }

    private fun timed(w: String) = Lyrics(listOf(l(1f, w)), true)
    private fun plain(w: String) = Lyrics(listOf(l(0f, w)), false)

    @Test
    fun theMostTrustedAnswerWinsWithoutWaitingForWorse() {
        val all = listOf(true, true, true)
        // YouTube Music's timed lyrics: nothing could beat them, so no waiting for the others.
        assertEquals(Engine.Pick.Of(0), Engine.settle(listOf(Said.Found(timed("a")), null, null), all, null))
        // LRCLIB's timed lyrics: YouTube Music could still have better ones, NetEase couldn't.
        assertNull(Engine.settle(listOf(null, Said.Found(timed("b")), null), all, null))
        assertEquals(Engine.Pick.Of(1), Engine.settle(listOf(Said.Nothing, Said.Found(timed("b")), null), all, null))
        // Timed lyrics from anywhere beat plain ones from YouTube Music.
        assertEquals(Engine.Pick.Of(2), Engine.settle(listOf(Said.Found(plain("a")), Said.Failed(true), Said.Found(timed("c"))), all, null))
        // Plain lyrics wait for everyone, since anyone might have timed ones.
        assertNull(Engine.settle(listOf(Said.Found(plain("a")), null, Said.Nothing), all, null))
        // A place resting isn't waited for.
        assertEquals(Engine.Pick.Of(1), Engine.settle(listOf(null, Said.Found(timed("b")), null), listOf(false, true, true), null))
        // "Wrong lyrics?": those words are passed over.
        val wrong = timed("a").wordsKey()
        assertEquals(Engine.Pick.Of(1), Engine.settle(listOf(Said.Found(timed("a")), Said.Found(timed("b")), Said.Nothing), all, wrong))
        assertEquals(Engine.Pick.None, Engine.settle(listOf(Said.Nothing, Said.Nothing, Said.Nothing), all, null))
    }

    @Test
    fun youtubeMusicLinesAreTidied() {
        val x = Engine.fromLines(listOf(l(0f, "♪"), l(1.11f, "First"), l(4f, "♪"), l(6.5f, "Second"), l(9f, " ♪ ")))
        assertTrue(x.synced)
        assertEquals(listOf(l(1.11f, "First"), l(4f, ""), l(6.5f, "Second")), x.lines)
        // Timed for another cut: kept as words only.
        val w = Engine.fromWords(io.github.ferasbusiness666.segno.lyrics.YtMusic.Words.Timed(listOf(l(1f, "One"), l(2f, "Two"))), false)
        assertFalse(w.synced)
        assertEquals(2, w.lines.size)
    }

    @Test
    fun askingEveryPlaceKeepsTheBestAndRestsTheBroken() {
        Engine.wakeAll()
        val q = Query(id = "t", title = "Song", artist = "Artist", duration = 100f)
        val (found, heard) = Engine.askAll(q) { src, _ ->
            when (src) {
                Source.YtMusic -> Said.Nothing
                Source.Lrclib -> Said.Found(timed("from lrclib"))
                Source.NetEase -> {
                    Thread.sleep(50)
                    Said.Failed(false)
                }
            }
        }
        assertEquals(Source.Lrclib, found?.first)
        // NetEase, slower and worse than LRCLIB's timed lyrics, wasn't waited for.
        assertEquals(2, heard.answered)
        assertFalse(heard.complete)
        Engine.wakeAll()
        val (none, heard2) = Engine.askAll(q) { _, _ -> Said.Nothing }
        assertNull(none)
        assertEquals(Heard(3, complete = true, busy = false), heard2)
    }

    @Test
    fun lrclibAsksExactlyFirstAndStopsAtTheFirstTimedMatch() {
        val asked = ArrayList<String>()
        val q = Query(id = "t", title = "Shoft Kalam", artist = "Marwan Pablo", duration = 210f)
        val got = Lrclib.find(q, get = { url ->
            asked += url
            if (url.contains("/api/get?")) {
                Web.Answer.Text("""{"trackName":"Shoft Kalam","artistName":"Marwan Pablo","duration":209.0,"syncedLyrics":"[00:01.00]one\n[00:02.00]two"}""")
            } else {
                Web.Answer.Text("[]")
            }
        }, pause = {})
        assertTrue(got!!.synced)
        assertEquals(1, asked.size)
        assertTrue(asked[0], asked[0].contains("track_name=Shoft%20Kalam&artist_name=Marwan%20Pablo&duration=210"))
    }

    @Test
    fun lrclibBusyTwiceIsAFailureNotAMiss() {
        val q = Query(id = "t", title = "Song", artist = "Artist", duration = 200f)
        try {
            Lrclib.find(q, get = { Web.Answer.Busy(1) }, pause = {})
            fail("busy should fail")
        } catch (e: Lrclib.Busy) {
            // As desktop: stop asking for now, and say it's busy.
        }
    }
}

/** The worker loop and the store, as desktop tests them. */
class LyricsWorkerTest {
    private fun query(id: String, force: Boolean = false, ahead: Boolean = false) = Query(id = id, title = "t", artist = "a", duration = 100f, force = force, ahead = ahead)

    /** A worker with a pretend site that finds every song on the second ask. */
    private fun withFakeSite(): Triple<Worker, LinkedBlockingQueue<Pair<String, String>>, MutableList<Pair<String, Boolean>>> {
        val out = LinkedBlockingQueue<Pair<String, String>>()
        val asked = Collections.synchronizedList(ArrayList<Pair<String, Boolean>>())
        val look = { q: Query, lastTry: Boolean ->
            val before = asked.count { it.first == q.id }
            asked += q.id to (lastTry || q.force)
            when {
                before > 0 -> Store.Step.Done(LyricsState.Found(Lyrics.plain("found")))
                lastTry || q.force -> Store.Step.Done(LyricsState.Missing)
                else -> Store.Step.NotYet
            }
        }
        val w = Worker(look, { id, st -> out.put(id to st.javaClass.simpleName.take(5)) }, followUpMs = 60)
        return Triple(w, out, asked)
    }

    @Test
    fun asksAgainALittleLater() {
        val (w, out, asked) = withFakeSite()
        w.send(query("1"))
        assertNull(out.poll(30, TimeUnit.MILLISECONDS))
        assertEquals("1" to "Found", out.poll(2, TimeUnit.SECONDS))
        assertEquals(listOf("1" to false, "1" to true), asked.toList())
    }

    @Test
    fun aNewSongDropsTheFollowUp() {
        val (w, out, asked) = withFakeSite()
        w.send(query("1"))
        Thread.sleep(20)
        w.send(query("2", force = true))
        assertEquals("2" to "Missi", out.poll(2, TimeUnit.SECONDS))
        assertNull(out.poll(150, TimeUnit.MILLISECONDS))
        assertEquals(listOf("1" to false, "2" to true), asked.toList())
    }

    @Test
    fun readsAheadQuietly() {
        val (w, out, asked) = withFakeSite()
        w.send(query("7", ahead = true))
        assertNull(out.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(listOf("7" to false), asked.toList())
        w.send(query("1", force = true))
        assertEquals("1" to "Missi", out.poll(2, TimeUnit.SECONDS))
    }

    private fun tmp(): File = Files.createTempDirectory("segno-lyrics").toFile()

    @Test
    fun sidecarBeatsEverything() {
        val dir = tmp()
        File(dir, "a.b.lrc").writeText("[00:01.00]mine")
        val store = Store(File(dir, "cache")) { fail("nothing should be asked"); null to Heard(0, false, false) }
        val q = Query(id = "local:1", songPath = File(dir, "a.b.mp3").path, title = "x", artist = "y", duration = 100f)
        assertEquals(LyricsState.Found(Lyrics(listOf(Line(1f, "mine")), true)), store.saved(q))
        dir.deleteRecursively()
    }

    @Test
    fun foundLyricsAreKeptAndNotFoundIsRememberedOnlyWhenEveryoneAnswered() {
        val dir = tmp()
        var answer: Pair<Pair<Source, Lyrics>?, Heard> = (Source.Lrclib to Lyrics.parseLrc("[00:01.00]one")) to Heard(2, true, false)
        val store = Store(dir) { answer }
        val q = Query(id = "yt:x", title = "Song", artist = "Artist", duration = 200f)
        assertEquals(Store.Step.Done(LyricsState.Found(Lyrics.parseLrc("[00:01.00]one"))), store.lookup(q, false))
        // Saved: no asking the second time.
        answer = null to Heard(0, false, false)
        assertEquals(LyricsState.Found(Lyrics.parseLrc("[00:01.00]one")), store.saved(q))
        // Earlier/Later is kept.
        store.keepTiming(q, Lyrics.parseLrc("[00:01.25]one"))
        assertEquals(LyricsState.Found(Lyrics.parseLrc("[00:01.25]one")), store.saved(q))
        // "Wrong lyrics?" forgets it; nobody answering is a failure, not "not found".
        assertEquals(Store.Step.Done(LyricsState.Failed(false)), store.lookup(q.copy(avoid = 1), true))
        // Not found, but a place didn't answer: not remembered.
        answer = null to Heard(2, complete = false, busy = false)
        assertEquals(Store.Step.Done(LyricsState.Missing), store.lookup(q, true))
        assertNull(store.saved(q))
        // Everyone answered: remembered for a week (unless "Try again").
        answer = null to Heard(3, complete = true, busy = false)
        assertEquals(Store.Step.NotYet, store.lookup(q, false))
        assertEquals(Store.Step.Done(LyricsState.Missing), store.lookup(q, true))
        assertEquals(LyricsState.Missing, store.saved(q))
        assertNull(store.saved(q.copy(force = true)))
        // A busy LRCLIB is asked again.
        answer = null to Heard(2, complete = false, busy = true)
        assertEquals(Store.Step.Done(LyricsState.Failed(true)), store.lookup(q.copy(force = true), true))
        dir.deleteRecursively()
    }
}
