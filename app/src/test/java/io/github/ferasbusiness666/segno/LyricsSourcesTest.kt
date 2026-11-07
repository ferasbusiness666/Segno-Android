package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.lyrics.Engine
import io.github.ferasbusiness666.segno.lyrics.Line
import io.github.ferasbusiness666.segno.lyrics.Lyrics
import io.github.ferasbusiness666.segno.lyrics.NetEase
import io.github.ferasbusiness666.segno.lyrics.Query
import io.github.ferasbusiness666.segno.lyrics.Web
import io.github.ferasbusiness666.segno.lyrics.YtMusic
import io.github.ferasbusiness666.segno.lyrics.YtMusic.Words
import io.github.ferasbusiness666.segno.spotify.SpotifyMatch
import io.github.ferasbusiness666.segno.lyrics.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The readers, against real answers recorded by desktop's "Lyrics sources check" workflow. */
class LyricsSourcesTest {
    private fun data(name: String): Any? = Web.json(javaClass.classLoader!!.getResource("sources/$name")!!.readText())

    @Test
    fun readsSongsFromARealSearch() {
        val songs = YtMusic.songsIn(data("ytm-search-songs-getlucky.json"))
        assertTrue(songs.toString(), songs.size >= 3)
        val s = songs[0]
        assertEquals("4D7u5KF7SP8", s.id)
        assertEquals("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", s.title)
        assertEquals("Daft Punk, Pharrell Williams & Nile Rodgers", s.artist)
        assertEquals("Random Access Memories", s.album)
        assertEquals(370f, s.duration)
        assertTrue(s.thumb, s.thumb.contains("googleusercontent.com") && s.thumb.endsWith("=w544-h544-l90-rj"))
        // Arabic and several artists.
        val k = YtMusic.songsIn(data("ytm-search-songs-shoftkalam.json"))[0]
        assertEquals(Triple("Shoft Kalam", "Marwan Pablo, Lege-Cy & HatemBas", 210f), Triple(k.title, k.artist, k.duration))
    }

    @Test
    fun tellsSongsFromMusicVideosAndFindsTheLyrics() {
        val t = YtMusic.trackIn(data("ytm-next-atv-getlucky.json"), "4D7u5KF7SP8")!!
        assertTrue(t.song)
        assertEquals(370f, t.duration)
        assertEquals("Daft Punk, Pharrell Williams & Nile Rodgers", t.artist)
        assertEquals("MPLYt_K8qWMWVqXGi-8", t.lyrics)
        val v = YtMusic.trackIn(data("ytm-next-omv-blindinglights.json"), "4NRXx6U8ABQ")!!
        assertFalse("a music video", v.song)
        assertEquals(Triple("Blinding Lights (Official Video)", "The Weeknd", 263f), Triple(v.title, v.artist, v.duration))
        assertNotNull(v.lyrics)
    }

    @Test
    fun readsTimedUntimedAndMissingLyrics() {
        val timed = YtMusic.wordsIn(data("ytm-lyrics-timed-thickofit.json")) as Words.Timed
        assertEquals(Line(1.11f, "I'm in the thick of it, everybody knows"), timed.lines[0])
        assertTrue(timed.lines.zipWithNext().all { (a, b) -> a.at <= b.at })
        // A music video's lyrics come without times: plain.
        val plain = YtMusic.wordsIn(data("ytm-lyrics-untimed-omv-blindinglights.json")) as Words.Plain
        assertTrue(plain.text, plain.text.startsWith("Yeah"))
        assertNull(YtMusic.wordsIn(data("ytm-lyrics-none-shoftkalam.json")))
    }

    @Test
    fun smallReaders() {
        assertEquals(249f, YtMusic.clock("4:09"))
        assertEquals(3723f, YtMusic.clock("1:02:03"))
        assertNull(YtMusic.clock("1.9B plays"))
        assertNull(YtMusic.clock("2013"))
        assertEquals(8, YtMusic.today().length)
        assertTrue(YtMusic.today().startsWith("20"))
    }

    @Test
    fun readsANetEaseSearchAndLyrics() {
        val s = NetEase.songsIn(data("netease-search-shoftkalam.json"))[0]
        assertEquals(Triple(3407079783L, "Shoft Kalam", "Marwan Pablo, Lege-Cy, HatemBas"), Triple(s.id, s.title, s.artist))
        assertEquals(209.49f, s.duration, 0.01f)
        val lrc = NetEase.lrcIn(data("netease-lyric-shoftkalam.json"))!!
        assertFalse("credits are left out", lrc.contains("作曲"))
        assertTrue(lrc.contains("حاتم، حاتم، بس"))
        val l = Lyrics.parseLrc(lrc)
        assertTrue(l.synced && l.lines.size > 20)
        assertNotNull(Engine.netEaseLyrics(lrc))
        val lucky = NetEase.lrcIn(data("netease-lyric-getlucky.json"))!!
        assertFalse(lucky.contains("作曲") || lucky.contains("制作人"))
    }

    @Test
    fun netEaseCreditsAreToldFromWords() {
        assertTrue(NetEase.isCredit("[00:00.000] 作曲 : Pharrell Williams"))
        assertTrue(NetEase.isCredit("[00:01.000] 制作人：The Weeknd"))
        assertFalse(NetEase.isCredit("[00:12.10]Like the legend of the phoenix"))
        assertFalse(NetEase.isCredit("[00:30.00]Note: this isn't a credit"))
        assertFalse(NetEase.isCredit("[00:04.945]حاتم، حاتم، بس"))
        assertNull(NetEase.lrcIn(Web.json("""{"nolyric": true}""")))
        // "纯音乐，请欣赏" (an instrumental) isn't lyrics.
        assertNull(Engine.netEaseLyrics("[00:00.00]纯音乐，请欣赏"))
    }

    /** YouTube Music's calls answered from the recorded files. */
    private inner class Recorded : Engine.YtmApi {
        val asked = ArrayList<String>()
        override fun track(id: String): YtMusic.Track {
            asked += "next $id"
            val file = if (id == "4NRXx6U8ABQ") "ytm-next-omv-blindinglights.json" else "ytm-next-atv-getlucky.json"
            return YtMusic.trackIn(data(file), id)!!
        }

        override fun searchSongs(query: String): List<YtMusic.Song> {
            asked += "search $query"
            return YtMusic.songsIn(data("ytm-search-songs-getlucky.json"))
        }

        override fun lyrics(browseId: String): Words? {
            asked += "browse $browseId"
            // The Get Lucky song's id gets timed words; the music video's own get plain ones.
            return if (browseId == "MPLYt_K8qWMWVqXGi-8") YtMusic.wordsIn(data("ytm-lyrics-timed-thickofit.json")) else YtMusic.wordsIn(data("ytm-lyrics-untimed-omv-blindinglights.json"))
        }
    }

    @Test
    fun anOfficialSongUsesItsOwnTimedLyrics() {
        val ytm = Recorded()
        val q = Query(id = "yt:4D7u5KF7SP8", title = "Get Lucky (feat. Pharrell Williams & Nile Rodgers)", artist = "Daft Punk", fromVideo = true, video = "4D7u5KF7SP8", duration = 369f)
        val l = Engine.fromYtMusic(q, ytm)!!
        assertTrue(l.synced)
        assertEquals(listOf("next 4D7u5KF7SP8", "browse MPLYt_K8qWMWVqXGi-8"), ytm.asked)
        // Played from a cut 30 s longer: the words, not the timing.
        assertFalse(Engine.fromYtMusic(q.copy(duration = 400f), Recorded())!!.synced)
    }

    @Test
    fun aMusicVideoLooksForItsSongThenFallsBackToItsOwnWords() {
        val ytm = Recorded()
        val q = Query(id = "yt:4NRXx6U8ABQ", title = "The Weeknd - Blinding Lights (Official Video)", artist = "TheWeekndVEVO", fromVideo = true, video = "4NRXx6U8ABQ", duration = 263f)
        val l = Engine.fromYtMusic(q, ytm)!!
        // The search (answered with Get Lucky songs here) has no twin: the video's own words, never timed.
        assertFalse(l.synced)
        assertEquals("next 4NRXx6U8ABQ", ytm.asked[0])
        assertEquals("search Blinding Lights (Official Video) The Weeknd", ytm.asked[1])
        assertTrue(ytm.asked.last().startsWith("browse "))
    }

    @Test
    fun withoutAVideoTheSongIsFoundByName() {
        val ytm = Recorded()
        val q = Query(id = "sp:69kOkLUCkxIZYexIgSG8rq", title = "Get Lucky (feat. Pharrell Williams and Nile Rodgers)", artist = "Daft Punk, Pharrell Williams, Nile Rodgers", duration = 369f)
        val l = Engine.fromYtMusic(q, ytm)!!
        assertTrue(l.synced)
        assertEquals("search Get Lucky (feat. Pharrell Williams and Nile Rodgers) Daft Punk", ytm.asked[0])
        assertEquals("next 4D7u5KF7SP8", ytm.asked[1])
    }

    @Test
    fun spotifySongsFindTheirTwinOnYouTubeMusic() {
        fun song(id: String, title: String, artist: String, d: Float) = YtMusic.Song(id, title, artist, duration = d)
        val found = listOf(
            song("liveLucky01", "Get Lucky (Live)", "Daft Punk", 369f),
            song("radioLucky1", "Get Lucky (Radio Edit)", "Daft Punk, Pharrell Williams & Nile Rodgers", 248f),
            song("4D7u5KF7SP8", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk, Pharrell Williams & Nile Rodgers", 370f),
        )
        val title = "Get Lucky (feat. Pharrell Williams and Nile Rodgers)"
        val artist = "Daft Punk, Pharrell Williams, Nile Rodgers"
        // Not the live one, not the short edit: the album song.
        assertEquals("4D7u5KF7SP8", SpotifyMatch.pickMatch(title, artist, 369.6f, found))
        assertEquals("radioLucky1", SpotifyMatch.pickMatch("Get Lucky - Radio Edit", artist, 248.4f, found))
        // Another artist's song with the same name isn't it.
        assertNull(SpotifyMatch.pickMatch(title, "Someone Else", 369.6f, found))
        assertEquals(listOf("sped up"), Names.versionKinds("Levitating (Sped Up)"))
        assertTrue("whole words only", Names.versionKinds("Alive").isEmpty())
    }
}
