package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.importer.Importer
import io.github.ferasbusiness666.segno.importer.Importer.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterTest {
    @Test
    fun readsWhatWasPasted() {
        val text = "Check these:\nhttps://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x,\nyoutube.com/@SomeName\n" +
            "https://music.youtube.com/channel/UCabc123\nhttps://www.youtube.com/playlist?list=PLxyz\n" +
            "https://open.spotify.com/user/someuser?si=1\nhttps://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC\n" +
            "https://open.spotify.com/track/7qiZfU4dY1lWllzX7mPBI3\nhttps://www.youtube.com/playlist?list=PLxyz"
        assertEquals(
            listOf(
                Source.Link("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"),
                Source.Channel("https://www.youtube.com/@SomeName/playlists"),
                Source.Channel("https://www.youtube.com/channel/UCabc123/playlists"),
                Source.Link("https://www.youtube.com/playlist?list=PLxyz"),
                Source.Profile("https://open.spotify.com/user/someuser"),
                Source.Songs(listOf("4uLU6hMCjMI75M1A2tKUQC", "7qiZfU4dY1lWllzX7mPBI3")),
            ),
            Importer.sources(text),
        )
        assertTrue(Importer.sources("just some words").isEmpty())
    }

    @Test
    fun profiles() {
        assertEquals("https://open.spotify.com/user/abc.def", Importer.spotifyProfile("https://open.spotify.com/intl-de/user/abc.def?si=2"))
        assertEquals("https://open.spotify.com/user/abc", Importer.spotifyProfile("spotify:user:abc"))
        assertNull(Importer.spotifyProfile("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(Importer.spotifyProfile("https://www.youtube.com/@x"))
    }

    @Test
    fun shortLinks() {
        assertEquals("youtube.com/playlist?list=PL1", Importer.short("https://www.youtube.com/playlist?list=PL1"))
        assertEquals(48, Importer.short("https://example.com/" + "a".repeat(80)).length)
    }
}
