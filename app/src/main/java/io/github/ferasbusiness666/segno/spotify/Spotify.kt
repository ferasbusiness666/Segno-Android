package io.github.ferasbusiness666.segno.spotify

import io.github.ferasbusiness666.segno.core.Net
import io.github.ferasbusiness666.segno.core.Track
import okhttp3.Request
import java.io.IOException

/** Reads Spotify links over the network. Blocking; call it off the main thread. */
object Spotify {
    private fun unreachable() = SpotifyException("Couldn't reach Spotify. Check your internet connection.")

    private fun getPage(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Net.BROWSER_UA)
            .header("Accept-Language", "en")
            .build()
        try {
            Net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 404) throw SpotifyException("Spotify says this link doesn't exist. Is it private?")
                    throw unreachable()
                }
                return resp.body.string()
            }
        } catch (e: IOException) {
            throw unreachable()
        }
    }

    /** The songs of a Spotify song, album or playlist link, and the album or playlist itself. */
    fun resolve(link: String): Pair<List<Track>, RemotePlaylist?> {
        var url = link.trim()
        if (url.contains("spotify.link/")) {
            url = SpotifyParser.findCanonical(getPage(url)) ?: throw SpotifyException("Couldn't open this Spotify link.")
        }
        val (kind, id) = SpotifyParser.parseLink(url)
            ?: throw SpotifyException("Segno can open Spotify songs, albums and playlists. This link is something else.")
        val page = getPage("https://open.spotify.com/embed/$kind/$id")
        return SpotifyParser.parseEmbed(page, kind, id, url)
    }

    /** One song by its id, or null if it can't be read. */
    fun track(id: String): Track? = try {
        resolve("https://open.spotify.com/track/$id").first.firstOrNull()
    } catch (e: SpotifyException) {
        null
    }
}
