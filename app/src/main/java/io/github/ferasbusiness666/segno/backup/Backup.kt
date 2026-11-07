package io.github.ferasbusiness666.segno.backup

import android.content.Context
import android.net.Uri
import io.github.ferasbusiness666.segno.BuildConfig
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Loved
import io.github.ferasbusiness666.segno.data.Playlists
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.library.LocalMusic
import io.github.ferasbusiness666.segno.playback.QueueStore
import io.github.ferasbusiness666.segno.youtube.YouTube
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Saving everything to one backup file and bringing it back (Settings → Backup). Restoring adds
 * and never deletes: playlists with the same name get the songs they don't have, Loved songs
 * are added, settings come from the backup. All calls are blocking.
 */
object Backup {
    private const val MAX_BYTES = 64L * 1024 * 1024

    class Saved(val playlists: Int, val loved: Int)

    class Restored(
        /** Playlists in the backup, and how many of them were new here. */
        val playlists: Int,
        val newPlaylists: Int,
        val songsAdded: Int,
        val lovedAdded: Int,
        /** Phone files that aren't on this phone; they're kept, marked as missing. */
        val missing: Int,
        /** The backup's queue, to put back if nothing is playing. */
        val queue: BackupFormat.Queue?,
        val queueTracks: List<Track>,
    )

    /** "segno-backup-2026-11-07.json". */
    fun fileName(today: LocalDate = LocalDate.now()) = "segno-backup-$today.json"

    /** Everything to back up, as the file's text. */
    fun build(context: Context): Pair<String, Saved> {
        val paths = LocalMusic.songsWithPaths(context).associate { (t, path) -> t.id to path }
        fun song(t: Track) = BackupFormat.song(t, paths[t.id])
        val playlists = Playlists.all().map { p ->
            BackupFormat.Playlist(p.name, Playlists.tracks(p.id).map(::song), p.source, p.site, p.cover)
        }
        val loved = Loved.tracks().map(::song)
        val queue = QueueStore.load(context)?.let { q ->
            BackupFormat.Queue(q.tracks.map(::song), q.index, q.positionMs, q.shuffle, BackupFormat.repeatName(q.repeat))
        }
        val data = BackupFormat.Data(
            app = "Segno for Android",
            appVersion = BuildConfig.VERSION_NAME,
            created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            settings = BackupFormat.Settings(
                autoplay = Settings.autoplay,
                checkUpdates = Settings.checkUpdates,
                search = if (Settings.searchSource == YouTube.VIDEOS) "youtube" else "youtube_music",
                libraryPage = Settings.libraryPage,
                recentSearches = Settings.recentSearches,
            ),
            playlists = playlists,
            loved = loved,
            queue = queue,
        )
        return BackupFormat.write(data) to Saved(playlists.size, loved.size)
    }

    /** Writes the backup to [uri] (from the system's "save as" screen). */
    fun save(context: Context, uri: Uri): Saved {
        val (text, saved) = build(context)
        val r = context.contentResolver
        val out = runCatching { r.openOutputStream(uri, "wt") }.getOrNull() ?: r.openOutputStream(uri, "w")
            ?: throw IOException("Can't write to $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return saved
    }

    /** Reads the backup at [uri] and adds it to what's here. Throws BackupFormat.Unreadable for wrong files. */
    fun restore(context: Context, uri: Uri): Restored {
        val r = context.contentResolver
        val bytes = r.openInputStream(uri)?.use { input ->
            val buf = input.readNBytesCompat(MAX_BYTES + 1)
            if (buf.size > MAX_BYTES) throw BackupFormat.Unreadable(BackupFormat.Reason.NotABackup)
            buf
        } ?: throw IOException("Can't read $uri")
        return apply(context, BackupFormat.read(String(bytes, Charsets.UTF_8)))
    }

    fun apply(context: Context, data: BackupFormat.Data): Restored {
        val phone = BackupMerge.PhoneIndex(LocalMusic.songsWithPaths(context).map { (t, path) -> BackupMerge.PhoneSong(t, path) })
        var missing = 0
        val seenMissing = HashSet<String>()
        fun track(s: BackupFormat.Song): Track = BackupFormat.track(s, phone::match).also {
            if (it.isMissing && seenMissing.add(it.id)) missing++
        }

        // Playlists: merged by name, nothing removed.
        val incoming = data.playlists.map { p -> p to p.songs.map(::track) }
        val existing = Playlists.all().map { p -> BackupMerge.Existing(p.id, p.name, Playlists.tracks(p.id).map { it.id }.toSet()) }
        var created = 0
        var added = 0
        for (step in BackupMerge.plan(existing, incoming)) {
            when (step) {
                is BackupMerge.Step.Create -> {
                    Playlists.create(step.name, step.tracks, step.playlist.sourceUrl, step.playlist.site, step.playlist.cover)
                    created++
                    added += step.tracks.size
                }
                is BackupMerge.Step.Add -> if (step.tracks.isNotEmpty()) added += Playlists.add(step.id, step.tracks)
            }
        }

        // Loved songs: added.
        val lovedAdded = Loved.addAll(BackupMerge.newLoved(Loved.ids(), data.loved.map(::track)))

        // Settings: from the backup.
        data.settings.autoplay?.let { Settings.autoplay = it }
        data.settings.checkUpdates?.let { Settings.checkUpdates = it }
        data.settings.search?.let { Settings.searchSource = if (it == "youtube") YouTube.VIDEOS else YouTube.SONGS }
        data.settings.libraryPage?.takeIf { it in setOf("songs", "playlists", "albums", "artists") }?.let { Settings.libraryPage = it }
        data.settings.recentSearches?.let { Settings.recentSearches = it }

        val queueTracks = data.queue?.songs?.map(::track)?.filterNot { it.isMissing }.orEmpty()
        return Restored(
            playlists = data.playlists.map { it.name.trim().lowercase() }.distinct().size,
            newPlaylists = created,
            songsAdded = added,
            lovedAdded = lovedAdded,
            missing = missing,
            queue = data.queue,
            queueTracks = queueTracks,
        )
    }

    /** Reads at most [max] bytes (InputStream.readNBytes is Android 13+). */
    private fun java.io.InputStream.readNBytesCompat(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size.toLong(), max - out.size()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
