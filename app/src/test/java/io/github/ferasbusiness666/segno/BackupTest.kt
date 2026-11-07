package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.backup.BackupFormat
import io.github.ferasbusiness666.segno.backup.BackupFormat.Reason
import io.github.ferasbusiness666.segno.backup.BackupFormat.Song
import io.github.ferasbusiness666.segno.backup.BackupMerge
import io.github.ferasbusiness666.segno.backup.BackupMerge.Step
import io.github.ferasbusiness666.segno.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {
    private val yt = Track.youtube("5NV6Rdv1a3I", "Get Lucky", "Daft Punk", 248_000, "https://i.ytimg.com/vi/5NV6Rdv1a3I/hq.jpg")
    private val sp = Track(id = "sp:69kOkLUCkxIZYexIgSG8rq", title = "Get Lucky", artist = "Daft Punk", durationMs = 248_413, find = "Daft Punk Get Lucky", video = "5NV6Rdv1a3I")
    private val file = Track(id = "local:42", title = "Northern Lights", artist = "Atlas Echo", album = "Skies", durationMs = 201_000, uri = "content://media/external/audio/media/42", albumId = 7)
    private val path = "/storage/emulated/0/Music/Atlas Echo - Northern Lights.mp3"

    private fun data() = BackupFormat.Data(
        app = "Segno for Android",
        appVersion = "1.0.0",
        created = "2026-11-07T10:20:30Z",
        settings = BackupFormat.Settings(autoplay = false, checkUpdates = true, search = "youtube", libraryPage = "albums", recentSearches = listOf("fairuz", "a \"quoted\" one")),
        playlists = listOf(
            BackupFormat.Playlist("Road trip", listOf(BackupFormat.song(yt), BackupFormat.song(sp), BackupFormat.song(file, path)), "https://open.spotify.com/playlist/x", "Spotify", "https://i.scdn.co/x"),
            BackupFormat.Playlist("فيروز", emptyList()),
        ),
        loved = listOf(BackupFormat.song(yt)),
        queue = BackupFormat.Queue(listOf(BackupFormat.song(sp)), 0, 61_000, true, "all"),
    )

    @Test
    fun roundTrip() {
        val text = BackupFormat.write(data())
        // The version comes first, so any reader sees it before the rest.
        assertTrue(text.indexOf("\"segno_backup\"") in 0 until text.indexOf("\"app\""))
        assertEquals(data(), BackupFormat.read(text))
    }

    @Test
    fun songsCarryWhatAnotherDeviceNeeds() {
        assertEquals(Song("youtube", "Get Lucky", "Daft Punk", "", 248_000, yt.art, youtubeId = "5NV6Rdv1a3I"), BackupFormat.song(yt))
        val s = BackupFormat.song(sp)
        assertEquals("69kOkLUCkxIZYexIgSG8rq", s.spotifyId)
        assertEquals("5NV6Rdv1a3I", s.youtubeId)
        assertEquals("Daft Punk Get Lucky", s.search)
        val f = BackupFormat.song(file, path)
        assertEquals(Song("file", "Northern Lights", "Atlas Echo", "Skies", 201_000, path = path), f)
    }

    @Test
    fun tracksComeBack() {
        val none: (Song) -> Track? = { null }
        assertEquals(yt, BackupFormat.track(BackupFormat.song(yt), none))
        assertEquals(sp.copy(art = ""), BackupFormat.track(BackupFormat.song(sp), none))
        // A Spotify song without search words gets "artist title".
        assertEquals("Daft Punk Get Lucky", BackupFormat.track(Song("spotify", "Get Lucky", "Daft Punk", spotifyId = "x"), none).find)
        // A phone file found on this phone is that file.
        assertEquals(file, BackupFormat.track(BackupFormat.song(file, path)) { file })
    }

    @Test
    fun aFileNotOnThisPhoneIsKeptAsMissing() {
        val song = BackupFormat.song(file, path)
        val t = BackupFormat.track(song) { null }
        assertTrue(t.isMissing)
        assertEquals("Northern Lights", t.title)
        assertEquals("file://$path", t.uri)
        // Same song, same id, so a second restore doesn't add it twice.
        assertEquals(t.id, BackupFormat.track(song) { null }.id)
        // And it goes into the next backup as the same file song.
        assertEquals(song, BackupFormat.song(t))
    }

    @Test
    fun unknownAndIncompleteSongsAreSkipped() {
        val text = """{"segno_backup":1,"playlists":[{"name":"x","songs":[
            {"source":"soundcloud","title":"a"},{"source":"youtube","title":"no id"},{"source":"spotify","title":"no id"},
            {"source":"youtube","youtube_id":"abc","title":"ok"},{"source":"file","path":"/a/b.mp3"}]}]}"""
        val d = BackupFormat.read(text)
        assertEquals(listOf("abc", ""), d.playlists[0].songs.map { it.youtubeId })
        assertTrue(d.loved.isEmpty())
        assertNull(d.queue)
        assertNull(d.settings.autoplay)
    }

    @Test
    fun playlistsWithoutNamesAreSkipped() {
        val d = BackupFormat.read("""{"segno_backup":1,"playlists":[{"name":"  ","songs":[]},{"songs":[]},{"name":" Mix ","songs":[]}]}""")
        assertEquals(listOf("Mix"), d.playlists.map { it.name })
    }

    private fun reason(text: String): Reason? = try {
        BackupFormat.read(text)
        null
    } catch (e: BackupFormat.Unreadable) {
        e.reason
    }

    @Test
    fun wrongFilesAreRefusedKindly() {
        assertEquals(Reason.NotABackup, reason("hello"))
        assertEquals(Reason.NotABackup, reason("""{"name":"some other json"}"""))
        assertEquals(Reason.NotABackup, reason("""{"segno_backup":"one"}"""))
        assertEquals(Reason.Newer, reason("""{"segno_backup":2,"playlists":[]}"""))
        assertEquals(Reason.Damaged, reason("""{"segno_backup":1,"playlists":[{"name":"x","songs":[""" ))
        assertNull(reason("""{"segno_backup":1}"""))
    }

    @Test
    fun repeatNames() {
        for (mode in 0..2) assertEquals(mode, BackupFormat.repeatMode(BackupFormat.repeatName(mode)))
        assertEquals(0, BackupFormat.repeatMode("sideways"))
    }
}

class BackupMergeTest {
    private fun phone(id: Long, title: String, artist: String, ms: Long, path: String) =
        BackupMerge.PhoneSong(Track(id = "local:$id", title = title, artist = artist, durationMs = ms), path)

    private val index = BackupMerge.PhoneIndex(
        listOf(
            phone(1, "Northern Lights", "Atlas Echo", 201_000, "/sdcard/Music/a.mp3"),
            phone(2, "Northern Lights", "Atlas Echo", 330_000, "/sdcard/Music/live.mp3"),
            phone(3, "Desert Bloom", "", 180_000, "/sdcard/Download/b.flac"),
        ),
    )

    private fun song(title: String, artist: String, ms: Long, path: String = "") = Song("file", title, artist, durationMs = ms, path = path)

    @Test
    fun matchesTitleArtistAndLengthFirst() {
        assertEquals("local:1", index.match(song("northern lights ", "ATLAS ECHO", 202_500))?.id)
        assertEquals("local:2", index.match(song("Northern Lights", "Atlas Echo", 329_000))?.id)
        // No length in the backup: any one with that title and artist.
        assertEquals("Northern Lights", index.match(song("Northern Lights", "Atlas Echo", 0))?.title)
    }

    @Test
    fun thenThePath() {
        // Renamed tags, same file.
        assertEquals("local:3", index.match(song("Desert Bloom (2024)", "Kora Sun", 180_000, "/sdcard/Download/b.flac"))?.id)
    }

    @Test
    fun otherwiseNothing() {
        assertNull(index.match(song("Northern Lights", "Atlas Echo", 250_000)))
        assertNull(index.match(song("Unknown", "Nobody", 1_000, "/elsewhere/x.mp3")))
    }

    private fun t(id: String) = Track(id = id, title = id)

    private fun list(name: String) = BackupFormat.Playlist(name, emptyList())

    @Test
    fun playlistsMergeByNameAndNeverLoseSongs() {
        val existing = listOf(BackupMerge.Existing(7, "Road Trip", setOf("yt:a", "yt:b")))
        val steps = BackupMerge.plan(
            existing,
            listOf(
                list(" road trip") to listOf(t("yt:b"), t("yt:c"), t("yt:c")),
                list("New one") to listOf(t("yt:a"), t("missing:1")),
            ),
        )
        assertEquals(listOf(Step.Add(7, "Road Trip", listOf(t("yt:c"))), Step.Create("New one", listOf(t("yt:a"), t("missing:1")), list("New one"))), steps)
    }

    @Test
    fun twoWithOneNameBecomeOne() {
        val steps = BackupMerge.plan(emptyList(), listOf(list("Mix") to listOf(t("yt:a")), list("MIX") to listOf(t("yt:a"), t("yt:b"))))
        assertEquals(1, steps.size)
        val only = steps[0] as Step.Create
        assertEquals("Mix", only.name)
        assertEquals(listOf("yt:a", "yt:b"), only.tracks.map { it.id })
    }

    @Test
    fun restoringTwiceAddsNothing() {
        val first = BackupMerge.plan(emptyList(), listOf(list("Mix") to listOf(t("yt:a"), t("yt:b"))))
        val created = first.single() as Step.Create
        val again = BackupMerge.plan(listOf(BackupMerge.Existing(1, created.name, created.tracks.map { it.id }.toSet())), listOf(list("Mix") to created.tracks))
        assertEquals(listOf(Step.Add(1, "Mix", emptyList())), again)
    }

    @Test
    fun lovedSongsAreAddedNotReplaced() {
        assertEquals(listOf("yt:c", "yt:d"), BackupMerge.newLoved(setOf("yt:a", "yt:b"), listOf(t("yt:b"), t("yt:c"), t("yt:c"), t("yt:d"))).map { it.id })
        assertTrue(BackupMerge.newLoved(setOf("yt:a"), listOf(t("yt:a"))).isEmpty())
    }

    @Test
    fun missingSongsAreNotPlayable() {
        assertTrue(Track(id = Track.MISSING + "x", title = "x").isMissing)
        assertFalse(Track(id = "local:1", title = "x").isMissing)
        assertFalse(Track(id = "local:1", title = "x").isOnline)
    }

    @Test
    fun aBrokenFileFailsTheTest() {
        try {
            BackupFormat.read("")
            fail("an empty file should be refused")
        } catch (e: BackupFormat.Unreadable) {
            assertEquals(Reason.NotABackup, e.reason)
        }
    }
}
