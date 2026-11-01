package io.github.ferasbusiness666.segno.ui.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Changes
import io.github.ferasbusiness666.segno.data.History
import io.github.ferasbusiness666.segno.data.Playlist
import io.github.ferasbusiness666.segno.data.Playlists
import io.github.ferasbusiness666.segno.data.Related
import io.github.ferasbusiness666.segno.databinding.FragmentListBinding
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.CardAdapter
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.Row
import io.github.ferasbusiness666.segno.ui.common.RowAdapter
import io.github.ferasbusiness666.segno.ui.common.SectionAdapter
import io.github.ferasbusiness666.segno.ui.common.SongActions
import io.github.ferasbusiness666.segno.ui.common.TrackAdapter
import io.github.ferasbusiness666.segno.ui.common.ViewAdapter
import io.github.ferasbusiness666.segno.ui.common.openUrl
import io.github.ferasbusiness666.segno.ui.common.show
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import io.github.ferasbusiness666.segno.ui.detail.DetailFragment
import io.github.ferasbusiness666.segno.ui.library.LibraryFragment
import io.github.ferasbusiness666.segno.update.Updates
import io.github.ferasbusiness666.segno.databinding.ViewEmptyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: shortcuts, what you played lately, songs like the last one, and your playlists. When
 * nothing was played yet, it says how to start.
 */
class HomeFragment : Fragment(R.layout.fragment_list), MainActivity.Root {
    private var b: FragmentListBinding? = null
    private val main get() = requireActivity() as MainActivity

    private lateinit var update: ViewAdapter
    private lateinit var empty: ViewAdapter
    private val recentTitle = SectionAdapter(3)
    private val relatedTitle = SectionAdapter(4)
    private val playlistsTitle = SectionAdapter(5)
    private lateinit var recent: ViewAdapter
    private lateinit var cards: CardAdapter
    private lateinit var related: TrackAdapter
    private val playlists = RowAdapter()
    private var recentTracks: List<Track> = emptyList()
    private var relatedTracks: List<Track> = emptyList()
    private var release: Updates.Release? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentListBinding.bind(view)
        this.b = b
        cards = CardAdapter(
            onClick = { i, _ -> PlayerHub.play(main, recentTracks, i) },
            onLong = { SongActions.menu(main, it) },
        )
        related = TrackAdapter(
            onClick = { i, _ -> PlayerHub.play(main, relatedTracks, i) },
            onMore = { _, t -> SongActions.menu(main, t) },
        )
        val header = ViewAdapter(R.layout.item_home_header, 1) { v ->
            v.findViewById<View>(R.id.settings).setOnClickListener { main.openSettings() }
        }
        update = ViewAdapter(R.layout.item_update, 2) { v ->
            val r = release ?: return@ViewAdapter
            v.findViewById<TextView>(R.id.updateText).text = getString(R.string.update_banner, r.version)
            v.findViewById<View>(R.id.updateGet).setOnClickListener { requireContext().openUrl(r.url) }
            v.findViewById<View>(R.id.updateClose).setOnClickListener {
                Updates.dismiss()
                update.visible = false
            }
        }.apply { visible = false }
        val tiles = ViewAdapter(R.layout.item_tiles, 6) { v ->
            v.findViewById<View>(R.id.tileSongs).setOnClickListener { main.openLibrary(LibraryFragment.SONGS) }
            v.findViewById<View>(R.id.tileLoved).setOnClickListener { main.openDetail(DetailFragment.loved()) }
            v.findViewById<View>(R.id.tileDownloads).setOnClickListener { main.openDetail(DetailFragment.downloads()) }
            v.findViewById<View>(R.id.tileImport).setOnClickListener { main.openImport() }
        }
        recent = ViewAdapter(R.layout.item_carousel, 7) { v ->
            val rv = v as RecyclerView
            if (rv.adapter == null) {
                rv.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
                rv.setHasFixedSize(true)
                rv.adapter = cards
            }
        }.apply { visible = false }
        empty = ViewAdapter(R.layout.view_empty, 8) { v ->
            ViewEmptyBinding.bind(v).show(
                R.drawable.ic_music,
                getString(R.string.home_empty_title),
                getString(R.string.home_empty_body),
                getString(R.string.home_empty_button),
            ) { main.openSearch() }
        }.apply { visible = false }

        val config = ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build()
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.setHasFixedSize(true)
        b.list.itemAnimator = null
        b.list.adapter = ConcatAdapter(config, header, update, tiles, empty, recentTitle, recent, relatedTitle, related, playlistsTitle, playlists)

        whileStarted {
            launch { combine(Changes.history, Changes.playlists) { a, c -> a + c }.collect { load() } }
            launch { PlayerHub.state.collect { related.setNow(it.track?.id) } }
            launch { Downloads.states.collect { related.setDownloads(it) } }
            launch {
                Updates.available.collect {
                    release = Updates.bannerFor(it)
                    update.visible = release != null
                    update.refresh()
                }
            }
        }
    }

    private suspend fun load() {
        val (rec, rel, lists) = withContext(Dispatchers.IO) {
            Triple(History.recent(12), Related.latest(), Playlists.all())
        }
        recentTracks = rec
        cards.submitList(rec)
        recent.visible = rec.isNotEmpty()
        recentTitle.set(if (rec.isNotEmpty()) getString(R.string.home_recent) else null)

        val relList = rel?.second.orEmpty().distinctBy { it.id }.take(10)
        relatedTracks = relList
        related.submitList(relList)
        relatedTitle.set(if (relList.isNotEmpty()) getString(R.string.home_because, rel!!.first.title) else null)

        val shown = lists.take(6)
        playlists.submitList(shown.map { row(it) })
        playlistsTitle.set(
            if (shown.isNotEmpty()) getString(R.string.home_playlists) else null,
            if (lists.size > shown.size) getString(R.string.lib_title) else null,
        ) { main.openLibrary(LibraryFragment.PLAYLISTS) }

        empty.visible = rec.isEmpty()
    }

    private fun row(p: Playlist) = Row(
        key = "p${p.id}",
        title = p.name,
        sub = if (p.site.isNotEmpty()) {
            getString(R.string.lib_imported_sub, p.site, resources.getQuantityString(R.plurals.songs, p.count, p.count))
        } else {
            getString(R.string.lib_playlist_sub, Format.plural(p.count, "song", "songs"))
        },
        art = p.cover.ifEmpty { p.firstArt },
    ) { main.openDetail(DetailFragment.playlist(p.id)) }

    override fun scrollToTop() {
        b?.list?.smoothScrollToPosition(0)
    }

    override fun onDestroyView() {
        b?.list?.adapter = null
        b = null
        super.onDestroyView()
    }
}
