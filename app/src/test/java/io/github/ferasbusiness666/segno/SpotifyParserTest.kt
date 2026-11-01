package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.spotify.SpotifyException
import io.github.ferasbusiness666.segno.spotify.SpotifyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyParserTest {
    private fun page(entity: String) =
        """<html><head></head><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script></body></html>"""

    @Test
    fun findsIdsInCopiedTextAndPages() {
        val copied = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC\r\nhttps://open.spotify.com/intl-de/track/7qiZfU4dY1lWllzX7mPBI3?si=abc\nspotify:track:4uLU6hMCjMI75M1A2tKUQC\nspotify:track:0VjIjW4GlUZAMYd2vXMi3b"
        assertEquals(listOf("4uLU6hMCjMI75M1A2tKUQC", "7qiZfU4dY1lWllzX7mPBI3", "0VjIjW4GlUZAMYd2vXMi3b"), SpotifyParser.trackIdsIn(copied))
        val p = """<a href="/playlist/37i9dQZF1DXcBWIGoYBM5M">Hits</a> "uri":"spotify:playlist:37i9dQZF1DX0XUsuxWHRQd" /playlist/short /playlist/37i9dQZF1DXcBWIGoYBM5M"""
        assertEquals(listOf("37i9dQZF1DXcBWIGoYBM5M", "37i9dQZF1DX0XUsuxWHRQd"), SpotifyParser.idsIn(p, "playlist"))
        assertTrue(SpotifyParser.trackIdsIn("no links here").isEmpty())
    }

    @Test
    fun links() {
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", SpotifyParser.parseLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("track" to "4uLU6hMCjMI75M1A2tKUQC", SpotifyParser.parseLink("https://open.spotify.com/intl-ar/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("album" to "1ATL5GLyefJaxhQzSPVrLX", SpotifyParser.parseLink("spotify:album:1ATL5GLyefJaxhQzSPVrLX"))
        assertEquals("album" to "1ATL5GLyefJaxhQzSPVrLX", SpotifyParser.parseLink("https://open.spotify.com/embed/album/1ATL5GLyefJaxhQzSPVrLX"))
        assertNull(SpotifyParser.parseLink("https://open.spotify.com/artist/123"))
        assertNull(SpotifyParser.parseLink("https://example.com/"))
        assertTrue(SpotifyParser.isSpotify("https://open.spotify.com/track/x"))
        assertTrue(SpotifyParser.isSpotify("https://spotify.link/abc"))
    }

    @Test
    fun canonicalLink() {
        assertEquals(
            "https://open.spotify.com/playlist/abc?si=1",
            SpotifyParser.findCanonical("""<meta property="og:url" content="https://open.spotify.com/playlist/abc?si=1"/>"""),
        )
        assertNull(SpotifyParser.findCanonical("<html></html>"))
    }

    @Test
    fun playlistPage() {
        val p = page(
            """{"type":"playlist","name":"Road Trip","uri":"spotify:playlist:abc","subtitle":"Feras",
               "coverArt":{"sources":[{"url":"https://i.scdn.co/small","width":60},{"url":"https://i.scdn.co/big","width":640}]},
               "trackList":[
                 {"uri":"spotify:track:t1","title":"Blinding Lights","subtitle":"The Weeknd","duration":200040},
                 {"uri":"spotify:track:t2","title":"Levitating","subtitle":"Dua Lipa, DaBaby","duration":203064},
                 {"uri":"","title":"Broken"}
               ]}""",
        )
        val (items, info) = SpotifyParser.parseEmbed(p, "playlist", "abc", "https://open.spotify.com/playlist/abc")
        assertEquals(2, items.size)
        assertEquals("sp:t1", items[0].id)
        assertEquals("The Weeknd - Blinding Lights audio", items[0].find)
        assertEquals("Dua Lipa - Levitating audio", items[1].find)
        assertEquals(203064L, items[1].durationMs)
        assertTrue(items[0].art.isEmpty())
        assertEquals("Road Trip", info!!.title)
        assertEquals("https://i.scdn.co/big", info.cover)
        assertEquals("Feras", info.owner)
    }

    @Test
    fun albumPageSharesTheCover() {
        val p = page(
            """{"type":"album","name":"After Hours","uri":"spotify:album:al","subtitle":"The Weeknd",
               "coverArt":{"sources":[{"url":"https://i.scdn.co/cover","width":300}]},
               "trackList":[{"uri":"spotify:track:t1","title":"Alone Again","subtitle":"The Weeknd","duration":{"totalMilliseconds":250000}}]}""",
        )
        val (items, _) = SpotifyParser.parseEmbed(p, "album", "al", "x")
        assertEquals("https://i.scdn.co/cover", items[0].art)
        assertEquals("After Hours", items[0].album)
        assertEquals(250000L, items[0].durationMs)
    }

    @Test
    fun trackPage() {
        val p = page(
            """{"type":"track","name":"Blinding Lights","uri":"spotify:track:t1","artists":[{"name":"The Weeknd"}],"duration":200040,
               "visualIdentity":{"image":[{"url":"https://i.scdn.co/cover","maxWidth":640}]}}""",
        )
        val (items, info) = SpotifyParser.parseEmbed(p, "track", "t1", "x")
        assertNull(info)
        assertEquals("Blinding Lights", items[0].title)
        assertEquals("The Weeknd", items[0].artist)
        assertEquals("https://i.scdn.co/cover", items[0].art)
    }

    @Test
    fun entityFoundAnywhere() {
        val p = """<script id="__NEXT_DATA__" type="application/json">{"props":{"x":[{"y":{"type":"track","name":"Song","uri":"spotify:track:zz","artists":[{"name":"A"}]}}]}}</script>"""
        val (items, _) = SpotifyParser.parseEmbed(p, "track", "zz", "x")
        assertEquals("sp:zz", items[0].id)
    }

    @Test
    fun badPagesSayWhy() {
        try {
            SpotifyParser.parseEmbed("<html>nothing</html>", "playlist", "a", "x")
            fail()
        } catch (e: SpotifyException) {
            assertTrue(e.message!!.contains("Spotify changed its page"))
        }
        try {
            SpotifyParser.parseEmbed(page("""{"type":"playlist","name":"Empty","uri":"spotify:playlist:a","trackList":[]}"""), "playlist", "a", "x")
            fail()
        } catch (e: SpotifyException) {
            assertTrue(e.message!!.contains("no songs"))
        }
    }
}
