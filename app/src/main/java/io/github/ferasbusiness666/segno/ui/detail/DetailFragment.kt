package io.github.ferasbusiness666.segno.ui.detail

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Changes
import io.github.ferasbusiness666.segno.data.Loved
import io.github.ferasbusiness666.segno.data.Playlists
import io.github.ferasbusiness666.segno.databinding.FragmentDetailBinding
import io.github.ferasbusiness666.segno.databinding.ViewEmptyBinding
import io.github.ferasbusiness666.segno.download.DownloadService
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.library.LocalMusic
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.ArtView
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.SongActions
import io.github.ferasbusiness666.segno.ui.common.TrackAdapter
import io.github.ferasbusiness666.segno.ui.common.ViewAdapter
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.show
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A list of songs: a playlist, Loved songs, Downloads, or an album or artist on the phone. */
class DetailFragment : Fragment(R.layout.fragment_detail) {
    private var b: FragmentDetailBinding? = null
    private val main get() = requireActivity() as MainActivity
    private val kind get() = requireArguments().getString(KIND)!!
    private val id get() = requireArguments().getLong(ID)
    private val name get() = requireArguments().getString(NAME).orEmpty()

    private lateinit var adapter: TrackAdapter
    private lateinit var header: ViewAdapter
    private lateinit var empty: ViewAdapter
    private var tracks: List<Track> = emptyList()
    private var title = ""
    private var sub = ""
    private var art = ""
    private var gone = false
    private var touch: ItemTouchHelper? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentDetailBinding.bind(view)
        this.b = b
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        val canReorder = kind == PLAYLIST
        adapter = TrackAdapter(
            onClick = { i, _ -> PlayerHub.play(main, tracks, i) },
            onMore = { _, t -> SongActions.menu(main, t, playlistId = if (kind == PLAYLIST) id else null) },
            onStartDrag = if (canReorder) ({ h -> touch?.startDrag(h) }) else null,
        )
        header = ViewAdapter(R.layout.item_detail_header, 1) { v -> bindHeader(v) }
        empty = ViewAdapter(R.layout.view_empty, 2) { v -> bindEmpty(ViewEmptyBinding.bind(v)) }.apply { visible = false }
        val config = ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build()
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.setHasFixedSize(true)
        b.list.adapter = ConcatAdapter(config, header, empty, adapter)
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // The small title shows once the big one scrolls away.
                val lm = rv.layoutManager as LinearLayoutManager
                val show = lm.findFirstVisibleItemPosition() > 0 || rv.computeVerticalScrollOffset() > rv.dp(200)
                val target = if (show) 1f else 0f
                if (b.barTitle.alpha != target && b.barTitle.tag != target) {
                    b.barTitle.tag = target
                    b.barTitle.animate().alpha(target).setDuration(150).start()
                }
            }
        })
        if (canReorder) {
            touch = ItemTouchHelper(DragCallback()).also { it.attachToRecyclerView(b.list) }
        }
        whileStarted {
            launch { changes().collect { load() } }
            launch { PlayerHub.state.collect { adapter.setNow(it.track?.id) } }
            launch { Downloads.states.collect { adapter.setDownloads(it); if (kind == DOWNLOADS) header.refresh() } }
        }
    }

    private fun changes(): Flow<Long> = when (kind) {
        PLAYLIST -> Changes.playlists
        LOVED -> Changes.loved
        DOWNLOADS -> Changes.downloads
        else -> flowOf(0L)
    }

    private suspend fun load() {
        val ctx = requireContext().applicationContext
        when (kind) {
            PLAYLIST -> {
                val (p, list) = withContext(Dispatchers.IO) { Playlists.get(id) to Playlists.tracks(id) }
                if (p == null) {
                    gone = true
                    tracks = emptyList()
                } else {
                    title = p.name
                    art = p.cover.ifEmpty { p.firstArt }
                    tracks = list
                    sub = listOfNotNull(
                        if (p.site.isNotEmpty() && p.source.isNotEmpty()) p.site else null,
                        Format.songsAndLength(list.size, list.sumOf { it.durationMs }),
                    ).joinToString(" · ")
                }
            }
            LOVED -> {
                tracks = withContext(Dispatchers.IO) { Loved.tracks() }
                title = getString(R.string.loved_title)
                sub = Format.songsAndLength(tracks.size, tracks.sumOf { it.durationMs })
            }
            DOWNLOADS -> {
                tracks = withContext(Dispatchers.IO) { Downloads.allTracks() }
                title = getString(R.string.downloads_title)
                sub = Format.songsAndLength(tracks.size, tracks.sumOf { it.durationMs })
            }
            ALBUM -> {
                tracks = withContext(Dispatchers.IO) { LocalMusic.albumSongs(ctx, id) }
                title = name
                art = "content://media/external/audio/albumart/$id"
                val artist = tracks.map { it.artist }.distinct().singleOrNull().orEmpty()
                sub = Format.sub(artist, Format.songsAndLength(tracks.size, tracks.sumOf { it.durationMs }))
            }
            ARTIST -> {
                tracks = withContext(Dispatchers.IO) { LocalMusic.artistSongs(ctx, id) }
                title = name
                sub = Format.songsAndLength(tracks.size, tracks.sumOf { it.durationMs })
            }
        }
        tracks = tracks.distinctBy { it.id }
        b?.barTitle?.text = title
        adapter.submitList(tracks)
        adapter.setDownloads(Downloads.states.value)
        header.refresh()
        empty.visible = tracks.isEmpty()
        empty.refresh()
    }

    private fun bindHeader(v: View) {
        val headArt = v.findViewById<ArtView>(R.id.headArt)
        val headIcon = v.findViewById<ImageView>(R.id.headIcon)
        v.findViewById<TextView>(R.id.headKind).text = getString(
            when (kind) {
                PLAYLIST -> R.string.kind_playlist
                LOVED -> R.string.kind_favorites
                DOWNLOADS -> R.string.kind_on_phone
                ALBUM -> R.string.kind_album
                else -> R.string.kind_artist
            },
        )
        v.findViewById<TextView>(R.id.headTitle).text = title
        v.findViewById<TextView>(R.id.headSub).text = sub
        headArt.circle = kind == ARTIST
        when (kind) {
            LOVED, DOWNLOADS -> {
                headArt.setImageDrawable(null)
                headArt.setBackgroundResource(if (kind == LOVED) R.color.accent else R.color.raised_2)
                headIcon.isVisible = true
                headIcon.setImageResource(if (kind == LOVED) R.drawable.ic_heart_filled else R.drawable.ic_download)
            }
            ARTIST -> {
                headIcon.isVisible = false
                Images.placeholder(headArt, R.drawable.art_placeholder_artist)
            }
            else -> {
                headIcon.isVisible = false
                Images.url(headArt, Images.sized(art, v.dp(336)), if (kind == ALBUM) R.drawable.art_placeholder else R.drawable.art_placeholder_playlist)
            }
        }
        val has = tracks.isNotEmpty()
        v.findViewById<View>(R.id.headPlay).apply {
            isVisible = has
            setOnClickListener { PlayerHub.play(main, tracks, 0) }
        }
        v.findViewById<View>(R.id.headShuffle).apply {
            isVisible = has
            setOnClickListener { PlayerHub.play(main, tracks, 0, shuffle = true) }
        }
        val online = tracks.filter { it.isOnline }
        val states = Downloads.states.value
        val need = online.count { states[it.id]?.isDone != true }
        v.findViewById<ImageView>(R.id.headDownload).apply {
            isVisible = online.isNotEmpty() && kind != DOWNLOADS
            setImageResource(if (need == 0) R.drawable.ic_downloaded else R.drawable.ic_download)
            contentDescription = if (need == 0) getString(R.string.all_downloaded) else getString(R.string.download_n_more, need)
            setOnClickListener {
                if (need == 0) Messages.show(getString(R.string.all_downloaded)) else SongActions.download(main, online)
            }
        }
        v.findViewById<View>(R.id.headMore).apply {
            isVisible = kind == PLAYLIST || (kind == DOWNLOADS && states.values.any { it.isActive }) || has
            setOnClickListener { moreMenu() }
        }
    }

    private fun moreMenu() {
        val items = ArrayList<SongActions.Item>()
        if (tracks.isNotEmpty()) {
            items += SongActions.Item(R.drawable.ic_add_to_queue, getString(R.string.add_all_to_queue)) {
                PlayerHub.addToQueue(main, tracks)
                Messages.show(getString(R.string.toast_added_queue_n, tracks.size))
            }
            items += SongActions.Item(R.drawable.ic_list_plus, getString(R.string.menu_add_to_playlist)) { SongActions.addToPlaylist(main, tracks) }
        }
        if (kind == PLAYLIST) {
            items += SongActions.Item(R.drawable.ic_rename, getString(R.string.rename_playlist)) {
                SongActions.nameDialog(main, getString(R.string.rename_playlist), title) { n ->
                    lifecycleScope.launch(Dispatchers.IO) { Playlists.rename(id, n) }
                }
            }
            items += SongActions.Item(R.drawable.ic_trash, getString(R.string.delete_playlist)) { confirmDelete() }
        }
        if (kind == DOWNLOADS && Downloads.states.value.values.any { it.isActive }) {
            items += SongActions.Item(R.drawable.ic_close, getString(R.string.downloads_stop)) {
                val ctx = requireContext().applicationContext
                ctx.startService(android.content.Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_STOP))
            }
        }
        SongActions.sheet(main, title, sub, null, items, artUrl = art.ifEmpty { null })
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_playlist_q, title))
            .setMessage(R.string.delete_playlist_body)
            .setPositiveButton(R.string.delete) { _, _ ->
                val app = main
                app.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { Playlists.delete(id) }
                    Messages.show(app.getString(R.string.toast_playlist_deleted))
                }
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bindEmpty(e: ViewEmptyBinding) {
        when {
            gone -> e.show(R.drawable.ic_info, getString(R.string.gone_title), getString(R.string.gone_body))
            kind == LOVED -> e.show(R.drawable.ic_heart, getString(R.string.loved_empty_title), getString(R.string.loved_empty_body), getString(R.string.home_empty_button)) { main.openSearch() }
            kind == DOWNLOADS -> e.show(R.drawable.ic_download, getString(R.string.downloads_empty_title), getString(R.string.downloads_empty_body), getString(R.string.home_empty_button)) { main.openSearch() }
            else -> e.show(R.drawable.ic_list_plus, getString(R.string.playlist_empty_title), getString(R.string.playlist_empty_body), getString(R.string.home_empty_button)) { main.openSearch() }
        }
        e.root.setPadding(e.root.paddingLeft, e.root.dp(16), e.root.paddingRight, e.root.paddingBottom)
    }

    /** Drag songs by their handle to reorder a playlist. */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (vh is TrackAdapter.Holder) super.getMovementFlags(rv, vh) else 0

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            if (to !is TrackAdapter.Holder) return false
            val a = from.bindingAdapterPosition
            val c = to.bindingAdapterPosition
            if (a < 0 || c < 0) return false
            adapter.dragMove(a, c)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            val list = adapter.dragEnd() ?: return
            tracks = list.toList()
            val ids = tracks.map { it.id }
            lifecycleScope.launch(Dispatchers.IO) { Playlists.setOrder(id, ids) }
        }
    }

    override fun onDestroyView() {
        b?.list?.adapter = null
        b = null
        super.onDestroyView()
    }

    companion object {
        private const val KIND = "kind"
        private const val ID = "id"
        private const val NAME = "name"
        const val PLAYLIST = "playlist"
        const val LOVED = "loved"
        const val DOWNLOADS = "downloads"
        const val ALBUM = "album"
        const val ARTIST = "artist"

        private fun make(kind: String, id: Long = 0, name: String = "") =
            DetailFragment().apply { arguments = bundleOf(KIND to kind, ID to id, NAME to name) }

        fun playlist(id: Long) = make(PLAYLIST, id)
        fun loved() = make(LOVED)
        fun downloads() = make(DOWNLOADS)
        fun album(id: Long, name: String) = make(ALBUM, id, name)
        fun artist(id: Long, name: String) = make(ARTIST, id, name)
    }
}
