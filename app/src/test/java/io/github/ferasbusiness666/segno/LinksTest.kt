package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.youtube.Links
import io.github.ferasbusiness666.segno.youtube.Thumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun videoIds() {
        assertEquals("dQw4w9WgXcQ", Links.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx"))
        assertEquals("dQw4w9WgXcQ", Links.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", Links.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
        assertEquals("dQw4w9WgXcQ", Links.videoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(Links.videoId("https://www.youtube.com/playlist?list=PLxyz"))
        assertNull(Links.videoId("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun playlists() {
        assertEquals("PLxyz_1-2", Links.playlistId("https://www.youtube.com/playlist?list=PLxyz_1-2"))
        assertEquals("OLAK5uy_abc", Links.playlistId("https://music.youtube.com/playlist?list=OLAK5uy_abc&si=1"))
        assertNull(Links.playlistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun channels() {
        assertEquals("https://www.youtube.com/@name/playlists", Links.channelPlaylists("https://www.youtube.com/@name/videos"))
        assertEquals("https://www.youtube.com/c/Name/playlists", Links.channelPlaylists("https://youtube.com/c/Name"))
        assertEquals("https://www.youtube.com/channel/UCabc123/playlists", Links.channelPlaylists("https://music.youtube.com/channel/UCabc123"))
        assertNull(Links.channelPlaylists("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Links.channelPlaylists("https://www.youtube.com/playlist?list=PLx"))
    }

    @Test
    fun looksLikeLink() {
        assertTrue(Links.looksLikeLink("https://youtu.be/x"))
        assertTrue(Links.looksLikeLink("youtube.com/@name"))
        assertTrue(Links.looksLikeLink("spotify:track:abc"))
        assertFalse(Links.looksLikeLink("daft punk"))
        assertFalse(Links.looksLikeLink("one.two three"))
        assertEquals("https://youtube.com/@name", Links.fullUrl("youtube.com/@name"))
    }

    @Test
    fun thumbnailsAtTheSizeShown() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", Thumbs.sized("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc", 120))
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj",
            Thumbs.sized("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", 120),
        )
        assertEquals("https://i.scdn.co/image/x", Thumbs.sized("https://i.scdn.co/image/x", 120))
    }

    @Test
    fun format() {
        assertEquals("3:05", Format.time(185_000))
        assertEquals("1:02:03", Format.time(3_723_000))
        assertEquals("0:00", Format.time(-5))
        assertEquals("1,234,567", Format.digits(1_234_567))
        assertEquals("1 song", Format.plural(1, "song", "songs"))
        assertEquals("12 songs, 48 min", Format.songsAndLength(12, 48 * 60_000L))
        assertEquals("120 songs, 7 hr 5 min", Format.songsAndLength(120, (7 * 60 + 5) * 60_000L))
        assertEquals("Artist", Format.cleanArtist("Artist - Topic"))
        assertEquals("AC_DC - Back_ In Black", Format.safeFileName("AC/DC - Back: In  Black"))
        assertEquals("Song", Format.safeFileName("..."))
    }
}
