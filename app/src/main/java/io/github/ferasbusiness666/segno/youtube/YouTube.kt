package io.github.ferasbusiness666.segno.youtube

import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Net
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.spotify.RemotePlaylist
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

/**
 * Something went wrong with YouTube; [message] says what, in words to show. [network]: the
 * connection failed (worth trying again), rather than this song being unplayable.
 */
class YouTubeException(message: String, cause: Throwable? = null, val network: Boolean = false) : IOException(message, cause)

/**
 * YouTube through NewPipeExtractor: search, playlists and a channel's playlists. Every call is
 * blocking and goes to the network, so call it off the main thread. NewPipe is set up on first use.
 */
object YouTube {
    const val SONGS = "music_songs"
    const val VIDEOS = "videos"

    @Volatile private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val locale = Locale.getDefault()
            val country = locale.country.takeIf { it.length == 2 } ?: "US"
            NewPipe.init(OkDownloader(Net.client), Localization.fromLocale(locale), ContentCountry(country))
            ready = true
        }
    }

    val service: StreamingService
        get() {
            init()
            return ServiceList.YouTube
        }

    class SearchPage(val tracks: List<Track>, val next: Page?, internal val handler: SearchQueryHandler)

    fun search(query: String, filter: String): SearchPage = guard {
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        val info = SearchInfo.getInfo(service, handler)
        SearchPage(info.relatedItems.mapNotNull { it.toTrack() }, info.nextPage, handler)
    }

    fun more(page: SearchPage): SearchPage = guard {
        val next = page.next ?: return@guard SearchPage(emptyList(), null, page.handler)
        val more = SearchInfo.getMoreItems(service, page.handler, next)
        SearchPage(more.items.mapNotNull { it.toTrack() }, more.nextPage, page.handler)
    }

    /** One video, as a song. */
    fun video(videoId: String): Track = guard {
        val a = StreamResolver.resolve(videoId)
        Track.youtube(videoId, a.title, a.artist, a.durationMs, a.art)
    }

    /** A playlist's songs (up to [max]) and its name and picture. */
    fun playlist(url: String, max: Int = 1500): Pair<RemotePlaylist, List<Track>> = guard {
        val info = PlaylistInfo.getInfo(service, url)
        val tracks = info.relatedItems.mapNotNull { it.toTrack() }.toMutableList()
        var next = info.nextPage
        while (next != null && tracks.size < max) {
            val page = PlaylistInfo.getMoreItems(service, url, next)
            tracks += page.items.mapNotNull { it.toTrack() }
            next = page.nextPage
        }
        val cover = bestImage(info.thumbnails, 480).ifEmpty { tracks.firstOrNull()?.art.orEmpty() }
        RemotePlaylist(
            title = info.name.ifBlank { "YouTube playlist" },
            owner = Format.cleanArtist(info.uploaderName),
            url = url,
            cover = cover,
            site = "YouTube",
        ) to tracks.distinctBy { it.id }
    }

    /** A channel's name and its public playlists (their songs are read when imported). */
    fun channelPlaylists(url: String): Pair<String, List<RemotePlaylist>> = guard {
        val channel = ChannelInfo.getInfo(service, url)
        val tab = channel.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.PLAYLISTS) }
            ?: return@guard channel.name to emptyList()
        val info = ChannelTabInfo.getInfo(service, tab)
        val items = info.relatedItems.toMutableList()
        var next = info.nextPage
        var pages = 0
        while (next != null && pages < 10) {
            val page = ChannelTabInfo.getMoreItems(service, tab, next)
            items += page.items
            next = page.nextPage
            pages++
        }
        channel.name to items.filterIsInstance<PlaylistInfoItem>().map {
            RemotePlaylist(
                title = it.name,
                owner = channel.name,
                url = it.url,
                cover = bestImage(it.thumbnails, 480),
                site = "YouTube",
            )
        }
    }

    fun InfoItem.toTrack(): Track? {
        if (this !is StreamInfoItem) return null
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) return null
        val id = Links.videoId(url) ?: return null
        val seconds = duration.coerceAtLeast(0)
        return Track.youtube(
            videoId = id,
            title = name.orEmpty(),
            artist = Format.cleanArtist(uploaderName),
            durationMs = seconds * 1000,
            art = bestImage(thumbnails, 480).ifEmpty { Thumbs.video(id) },
        )
    }

    /** The smallest picture at least [px] wide, else the biggest. */
    fun bestImage(images: List<Image>?, px: Int): String {
        if (images.isNullOrEmpty()) return ""
        val known = images.filter { it.width > 0 }
        if (known.isEmpty()) return images.last().url
        return (known.filter { it.width >= px }.minByOrNull { it.width } ?: known.maxBy { it.width }).url
    }

    /** Runs [block], turning NewPipe's errors into a [YouTubeException] with words to show. */
    inline fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: YouTubeException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw YouTubeException("YouTube took too long to answer. Check your internet connection and try again.", e, network = true)
        } catch (e: IOException) {
            throw YouTubeException("Couldn't reach YouTube. Check your internet connection.", e, network = true)
        } catch (e: Exception) {
            val network = e !is ContentNotAvailableException &&
                generateSequence(e.cause) { it.cause }.any { it is IOException }
            throw YouTubeException(if (network) explain(IOException()) else explain(e), e, network)
        }
    }

    fun explain(e: Throwable): String = when (e) {
        is AgeRestrictedContentException -> "YouTube only shows this one to signed-in users, so Segno can't play it."
        is GeographicRestrictionException -> "This isn't available in your country."
        is YoutubeMusicPremiumContentException, is PaidContentException -> "This one is only for paying YouTube members."
        is PrivateContentException -> "This video is private."
        is ContentNotAvailableException -> "This isn't available on YouTube anymore."
        is SignInConfirmNotBotException, is ReCaptchaException ->
            "YouTube is asking Segno to slow down. Try again in a few minutes."
        is IOException -> "Couldn't reach YouTube. Check your internet connection."
        else -> "YouTube answered in a way Segno didn't understand. Try again, or update Segno if it keeps happening."
    }
}
