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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data class Loading(val query: String) : State
        data class Results(val query: String, val tracks: List<Track>, val canLoadMore: Boolean) : State
        data class Empty(val query: String) : State
        data class Failed(val query: String, val message: String, val offline: Boolean, val link: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Links to playlists go to the Import page. */
    private val _openImport = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openImport: SharedFlow<String> = _openImport

    private val _filter = MutableStateFlow(YouTube.SONGS)
    val filterFlow: StateFlow<String> = _filter
    val filter: String get() = _filter.value

    private var page: YouTube.SearchPage? = null
    private var job: Job? = null
    private var moreJob: Job? = null
    private var lastQuery = ""

    fun setFilter(f: String) {
        if (f == filter) return
        _filter.value = f
        viewModelScope.launch(Dispatchers.IO) { Settings.searchFilter = f }
        if (lastQuery.isNotEmpty() && !Links.looksLikeLink(lastQuery)) search(lastQuery)
    }

    fun loadFilter() {
        viewModelScope.launch {
            _filter.value = withContext(Dispatchers.IO) { Settings.searchFilter }
        }
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
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!isLink) remember(query)
                    if (isLink) link(Links.fullUrl(query)) else words(query)
                }
                if (result == null) {
                    _state.value = State.Idle
                    return@launch
                }
                _state.value = if (result.isEmpty()) State.Empty(query) else State.Results(query, result, page?.next != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeException) {
                _state.value = State.Failed(query, e.message.orEmpty(), e.network, isLink)
            } catch (e: SpotifyException) {
                _state.value = State.Failed(query, e.message.orEmpty(), false, isLink)
            } catch (e: Exception) {
                _state.value = State.Failed(query, YouTube.explain(e), false, isLink)
            }
        }
    }

    private fun words(query: String): List<Track> {
        val p = try {
            YouTube.search(query, filter)
        } catch (e: YouTubeException) {
            // YouTube Music search can break when YouTube changes it; plain YouTube still works.
            if (filter == YouTube.SONGS && !e.network) YouTube.search(query, YouTube.VIDEOS) else throw e
        }
        val got = if (p.tracks.isEmpty() && filter == YouTube.SONGS) YouTube.search(query, YouTube.VIDEOS) else p
        page = got
        return got.tracks.distinctBy { it.id }
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

    private fun remember(query: String) {
        val list = Settings.recentSearches.toMutableList()
        list.removeAll { it.equals(query, ignoreCase = true) }
        list.add(0, query)
        Settings.recentSearches = list
    }
}
