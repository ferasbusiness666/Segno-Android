package io.github.ferasbusiness666.segno.core

/**
 * One song, wherever it comes from.
 *
 * [id] says where: "yt:<video id>" for YouTube, "sp:<track id>" for Spotify (played from a
 * YouTube video Segno finds) and "local:<MediaStore id>" for music files on the phone.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    /** Cover picture: a web address, or empty (local songs load theirs from the phone). */
    val art: String = "",
    /** Spotify songs: the words to search YouTube for. */
    val find: String = "",
    /** Spotify songs: the YouTube video picked for it, once known. */
    val video: String = "",
    /** Local songs: the content:// address of the file. */
    val uri: String = "",
    /** Local songs: the album, for its cover. */
    val albumId: Long = 0,
) {
    val kind: Kind
        get() = when {
            id.startsWith(YT) -> Kind.YouTube
            id.startsWith(SP) -> Kind.Spotify
            else -> Kind.Local
        }

    val isOnline: Boolean get() = kind != Kind.Local

    /** The YouTube video that plays: the song's own, or the one found for a Spotify song. */
    val youtubeId: String?
        get() = when (kind) {
            Kind.YouTube -> id.removePrefix(YT)
            Kind.Spotify -> video.ifEmpty { null }
            Kind.Local -> null
        }

    val key: String get() = id.substringAfter(':')

    /** The page to open in a browser or share. */
    val pageUrl: String
        get() = when (kind) {
            Kind.YouTube -> "https://www.youtube.com/watch?v=$key"
            Kind.Spotify -> "https://open.spotify.com/track/$key"
            Kind.Local -> ""
        }

    enum class Kind { YouTube, Spotify, Local }

    companion object {
        const val YT = "yt:"
        const val SP = "sp:"
        const val LOCAL = "local:"

        fun youtube(videoId: String, title: String, artist: String, durationMs: Long, art: String = "") =
            Track(id = YT + videoId, title = title, artist = artist, durationMs = durationMs, art = art)
    }
}
