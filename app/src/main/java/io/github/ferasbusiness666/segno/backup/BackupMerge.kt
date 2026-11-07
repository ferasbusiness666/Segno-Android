package io.github.ferasbusiness666.segno.backup

import io.github.ferasbusiness666.segno.core.Track
import kotlin.math.abs

/**
 * How a backup joins what's already on the phone: nothing is ever deleted. Plain Kotlin, so it
 * can be tested without a phone.
 */
object BackupMerge {
    /** A music file on this phone: its track (title, artist, length) and its path. */
    class PhoneSong(val track: Track, val path: String)

    /**
     * Finds a backup's phone file on this phone: same title and artist (any case) and about the
     * same length first, then the same path.
     */
    class PhoneIndex(songs: List<PhoneSong>) {
        private val byName = songs.groupBy { key(it.track.title, it.track.artist) }
        private val byPath = songs.filter { it.path.isNotEmpty() }.associateBy { it.path }

        fun match(s: BackupFormat.Song): Track? {
            val named = byName[key(s.title, s.artist)].orEmpty()
            val close = named
                .filter { s.durationMs <= 0 || it.track.durationMs <= 0 || abs(it.track.durationMs - s.durationMs) <= LENGTH_SLACK_MS }
                .minByOrNull { abs(it.track.durationMs - s.durationMs) }
            if (close != null) return close.track
            return s.path.takeIf { it.isNotEmpty() }?.let { byPath[it]?.track }
        }

        private fun key(title: String, artist: String) = title.trim().lowercase() + "\u0000" + artist.trim().lowercase()
    }

    /** Lengths can differ a little between devices and tag readers. */
    const val LENGTH_SLACK_MS = 2_000L

    /** A playlist on the phone: its id, name and songs. */
    class Existing(val id: Long, val name: String, val trackIds: Set<String>)

    sealed interface Step {
        val name: String
        val tracks: List<Track>

        /** A new playlist with these songs. */
        data class Create(override val name: String, override val tracks: List<Track>, val playlist: BackupFormat.Playlist) : Step

        /** Songs to add at the end of playlist [id], which has the same name. */
        data class Add(val id: Long, override val name: String, override val tracks: List<Track>) : Step
    }

    /**
     * What to do with the backup's playlists: one with the same name (any case) gets the songs it
     * doesn't have yet; the others are made. Two in the backup with one name become one playlist.
     */
    fun plan(existing: List<Existing>, incoming: List<Pair<BackupFormat.Playlist, List<Track>>>): List<Step> {
        val have = existing.associateBy { norm(it.name) }
        val out = LinkedHashMap<String, Step>()
        val seen = HashMap<String, MutableSet<String>>()
        for ((p, tracks) in incoming) {
            val key = norm(p.name)
            val ids = seen.getOrPut(key) { have[key]?.trackIds?.toMutableSet() ?: mutableSetOf() }
            val fresh = tracks.filter { ids.add(it.id) }
            out[key] = when (val before = out[key]) {
                null -> have[key]?.let { Step.Add(it.id, it.name, fresh) } ?: Step.Create(p.name.trim(), fresh, p)
                is Step.Add -> before.copy(tracks = before.tracks + fresh)
                is Step.Create -> before.copy(tracks = before.tracks + fresh)
            }
        }
        return out.values.toList()
    }

    /** Loved songs from the backup that aren't loved yet, in the backup's order. */
    fun newLoved(lovedIds: Set<String>, incoming: List<Track>): List<Track> {
        val seen = HashSet(lovedIds)
        return incoming.filter { seen.add(it.id) }
    }

    private fun norm(name: String) = name.trim().lowercase()
}
