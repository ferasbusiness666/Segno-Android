package io.github.ferasbusiness666.segno.ui.importer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.data.Playlists
import io.github.ferasbusiness666.segno.importer.Importer
import io.github.ferasbusiness666.segno.importer.Importer.Found
import io.github.ferasbusiness666.segno.importer.Importer.Source
import io.github.ferasbusiness666.segno.spotify.RemotePlaylist
import io.github.ferasbusiness666.segno.spotify.SpotifyException
import io.github.ferasbusiness666.segno.youtube.YouTube
import io.github.ferasbusiness666.segno.youtube.YouTubeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    sealed interface Stage {
        data object Start : Stage
        data class Looking(val note: String) : Stage
        data class Pick(val found: List<Found>, val already: Set<String>, val problems: List<String>) : Stage
        data class Importing(val done: Int, val total: Int) : Stage
        data class Done(val playlists: Int, val songs: Int, val problems: List<String>) : Stage
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Start)
    val stage: StateFlow<Stage> = _stage
    private var job: Job? = null

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    fun reset() {
        job?.cancel()
        _stage.value = Stage.Start
    }

    /** Looks up everything in the pasted text. */
    fun look(text: String) {
        val sources = Importer.sources(text)
        if (sources.isEmpty()) {
            _stage.value = Stage.Pick(emptyList(), emptySet(), listOf(str(R.string.import_no_link)))
            return
        }
        job?.cancel()
        _stage.value = Stage.Looking(str(R.string.import_looking))
        job = viewModelScope.launch {
            val found = ArrayList<Found>()
            val problems = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (src in sources) {
                    try {
                        lookOne(src, found, problems)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Importer.UnsupportedLink) {
                        problems += str(R.string.import_unsupported, Importer.short((src as? Source.Link)?.url.orEmpty()))
                    } catch (e: Exception) {
                        val where = when (src) {
                            is Source.Link -> Importer.short(src.url)
                            is Source.Channel -> Importer.short(src.url)
                            else -> ""
                        }
                        val why = when (e) {
                            is YouTubeException, is SpotifyException -> e.message.orEmpty()
                            else -> YouTube.explain(e)
                        }
                        problems += if (where.isEmpty()) why else "$where: $why"
                    }
                }
            }
            val unique = found.distinctBy { it.info.url }
            val already = withContext(Dispatchers.IO) {
                unique.mapNotNull { f -> f.info.url.takeIf { Playlists.bySource(it) != null } }.toSet()
            }
            _stage.value = Stage.Pick(unique, already, problems)
        }
    }

    private fun note(s: String) {
        _stage.value = Stage.Looking(s)
    }

    private fun lookOne(src: Source, found: MutableList<Found>, problems: MutableList<String>) {
        when (src) {
            is Source.Link -> {
                note(str(R.string.import_reading, Importer.short(src.url)))
                val (songs, info) = Importer.read(src.url)
                when {
                    info == null -> problems += str(R.string.import_single_song, Importer.short(src.url))
                    songs.isEmpty() -> problems += str(R.string.import_no_songs, info.title)
                    else -> found += Found(info, songs)
                }
            }
            is Source.Channel -> {
                note(str(R.string.import_reading_channel))
                val (name, lists) = YouTube.channelPlaylists(src.url)
                if (lists.isEmpty()) {
                    problems += str(R.string.import_no_public, name.ifEmpty { str(R.string.import_this_channel) })
                } else {
                    found += lists.map { Found(it, null) }
                }
            }
            is Source.Profile -> problems += str(R.string.import_profile)
            is Source.Songs -> {
                val (songs, missed) = Importer.readSpotifySongs(src.ids) { done ->
                    note(str(R.string.import_reading_songs, done, src.ids.size))
                }
                if (missed > 0) problems += str(R.string.import_songs_missed, missed)
                if (songs.isNotEmpty()) {
                    val key = Integer.toHexString(src.ids.joinToString(",").hashCode())
                    found += Found(
                        RemotePlaylist(
                            title = str(R.string.import_songs_title),
                            owner = "",
                            url = "spotify:songs:$key",
                            cover = songs.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(),
                            site = "Spotify",
                        ),
                        songs,
                    )
                }
            }
        }
    }

    fun toggle(index: Int) {
        val s = _stage.value as? Stage.Pick ?: return
        val list = s.found.toMutableList()
        val f = list.getOrNull(index) ?: return
        list[index] = f.copy(picked = !f.picked)
        _stage.value = s.copy(found = list)
    }

    fun setAll(on: Boolean) {
        val s = _stage.value as? Stage.Pick ?: return
        _stage.value = s.copy(found = s.found.map { it.copy(picked = on) })
    }

    /** Saves the ticked playlists, reading the songs of those not read yet. */
    fun import() {
        val s = _stage.value as? Stage.Pick ?: return
        val picks = s.found.filter { it.picked }
        if (picks.isEmpty()) return
        job?.cancel()
        _stage.value = Stage.Importing(0, picks.size)
        job = viewModelScope.launch {
            var lists = 0
            var songs = 0
            val problems = ArrayList<String>()
            withContext(Dispatchers.IO) {
                picks.forEachIndexed { k, f ->
                    try {
                        val read = f.songs ?: Importer.read(f.info.url).first
                        if (read.isEmpty()) {
                            problems += str(R.string.import_no_songs, f.info.title)
                        } else {
                            val existing = Playlists.bySource(f.info.url)
                            if (existing != null) {
                                Playlists.add(existing, read)
                            } else {
                                Playlists.create(f.info.title, read, source = f.info.url, site = f.info.site, cover = f.info.cover)
                            }
                            lists++
                            songs += read.size
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val why = if (e is YouTubeException || e is SpotifyException) e.message.orEmpty() else YouTube.explain(e)
                        problems += "“${f.info.title}”: $why"
                    }
                    _stage.value = Stage.Importing(k + 1, picks.size)
                }
            }
            _stage.value = Stage.Done(lists, songs, problems)
        }
    }

    fun summary(d: Stage.Done): String =
        if (d.playlists == 0) {
            str(R.string.import_nothing)
        } else {
            str(R.string.import_done, Format.plural(d.playlists, "playlist", "playlists"), Format.plural(d.songs, "song", "songs"))
        }
}
