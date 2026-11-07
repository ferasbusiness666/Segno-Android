package io.github.ferasbusiness666.segno.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.spotify.Spotify
import io.github.ferasbusiness666.segno.spotify.SpotifyException
import io.github.ferasbusiness666.segno.spotify.SpotifyParser
import io.github.ferasbusiness666.segno.youtube.Links
import io.github.ferasbusiness666.segno.youtube.YouTube
import io.github.ferasbusiness666.segno.youtube.YouTubeException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data class Loading(val query: String) : State
        data class Results(val query: String, val tracks: List<Track>, val canLoadMore: Boolean) : State
        data class Empty(val query: String, val source: String) : State
        /** [musicDown]: YouTube Music failed (blocked in some countries), so YouTube is worth a try. */
        data class Failed(val query: String, val message: String, val offline: Boolean, val link: Boolean, val musicDown: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Links to playlists go to the Import page. */
    private val _openImport = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openImport: SharedFlow<String> = _openImport

    private var page: YouTube.SearchPage? = null
    private var job: Job? = null
    private var moreJob: Job? = null
    private var lastQuery = ""

    /** YouTube.SONGS (YouTube Music) or YouTube.VIDEOS (YouTube), remembered between visits. */
    private val _source = MutableStateFlow(YouTube.SONGS)
    val source: StateFlow<String> = _source

    fun loadSource() {
        viewModelScope.launch {
            _source.value = withContext(Dispatchers.IO) { Settings.searchSource }.takeIf { it == YouTube.VIDEOS } ?: YouTube.SONGS
        }
    }

    /** Switches between YouTube Music and YouTube, and searches again if there was a search. */
    fun setSource(s: String) {
        if (s == _source.value) return
        _source.value = s
        viewModelScope.launch(Dispatchers.IO) { Settings.searchSource = s }
        if (lastQuery.isNotEmpty() && !Links.looksLikeLink(lastQuery)) search(lastQuery)
    }

    fun clear() {
        job?.cancel()
        moreJob?.cancel()
        lastQuery = ""
        page = null
        _state.value = State.Idle
    }

    fun retry() {
        if (lastQuery.isNotEmpty()) search(lastQuery)
    }

    fun search(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        lastQuery = query
        job?.cancel()
        moreJob?.cancel()
        page = null
        _state.value = State.Loading(query)
        val isLink = Links.looksLikeLink(query)
        val from = _source.value
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!isLink) remember(query)
                    if (isLink) link(Links.fullUrl(query)) else words(query, from)
                }
                if (result == null) {
                    _state.value = State.Idle
                    return@launch
                }
                _state.value = if (result.isEmpty()) State.Empty(query, from) else State.Results(query, result, page?.next != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeException) {
                val musicDown = !isLink && !e.network && from == YouTube.SONGS
                _state.value = State.Failed(query, e.message.orEmpty(), e.network, isLink, musicDown)
            } catch (e: SpotifyException) {
                _state.value = State.Failed(query, e.message.orEmpty(), false, isLink)
            } catch (e: Exception) {
                _state.value = State.Failed(query, YouTube.explain(e), false, isLink)
            }
        }
    }

    /** Songs from YouTube Music, or videos from YouTube. One quiet retry, because a single failed answer is common. */
    private suspend fun words(query: String, from: String): List<Track> {
        val p = try {
            YouTube.search(query, from)
        } catch (e: YouTubeException) {
            delay(700)
            try {
                YouTube.search(query, from)
            } catch (again: YouTubeException) {
                throw if (again.network || again.cause is ReCaptchaException) again else YouTubeException(SEARCH_FAILED, again)
            }
        }
        page = p
        return p.tracks.distinctBy { it.id }
    }

    /** A pasted link: a song plays here; playlists and channels go to the Import page. Null: handed off. */
    private fun link(url: String): List<Track>? {
        Links.videoId(url)?.let { return listOf(YouTube.video(it)) }
        if (SpotifyParser.isSpotify(url)) {
            val kind = SpotifyParser.parseLink(url)?.first
            if (kind == "track" || url.contains("spotify.link/")) {
                val (tracks, list) = Spotify.resolve(url)
                if (list == null) return tracks
            }
            _openImport.tryEmit(url)
            return null
        }
        if (Links.playlistId(url) != null || Links.channelPlaylists(url) != null) {
            _openImport.tryEmit(url)
            return null
        }
        throw YouTubeException("Segno can open YouTube and Spotify links. This one is from somewhere else.")
    }

    fun loadMore() {
        val s = _state.value as? State.Results ?: return
        val p = page ?: return
        if (p.next == null || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            try {
                val more = withContext(Dispatchers.IO) { YouTube.more(p) }
                page = more
                val have = s.tracks.map { it.id }.toHashSet()
                val merged = s.tracks + more.tracks.filter { have.add(it.id) }
                if (_state.value == s) _state.value = State.Results(s.query, merged, more.next != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The first page is still there; scrolling again tries again.
            }
        }
    }

    private companion object {
        const val SEARCH_FAILED = "YouTube didn't answer the way Segno expected. Give it a moment and try again."
    }

    private fun remember(query: String) {
        val list = Settings.recentSearches.toMutableList()
        list.removeAll { it.equals(query, ignoreCase = true) }
        list.add(0, query)
        Settings.recentSearches = list
    }
}
