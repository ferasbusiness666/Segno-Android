package io.github.ferasbusiness666.segno.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Changes
import io.github.ferasbusiness666.segno.data.Playlist
import io.github.ferasbusiness666.segno.data.Playlists
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.FragmentLibraryBinding
import io.github.ferasbusiness666.segno.databinding.ItemAlbumBinding
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.library.LocalMusic
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.Row
import io.github.ferasbusiness666.segno.ui.common.RowAdapter
import io.github.ferasbusiness666.segno.ui.common.SongActions
import io.github.ferasbusiness666.segno.ui.common.TrackAdapter
import io.github.ferasbusiness666.segno.ui.common.ViewAdapter
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.show
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import io.github.ferasbusiness666.segno.ui.detail.DetailFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Library: your playlists, and the music files on the phone by song, album and artist. */
class LibraryFragment : Fragment(R.layout.fragment_library), MainActivity.Root {
    private var b: FragmentLibraryBinding? = null
    private val main get() = requireActivity() as MainActivity
    private var page = SONGS
    /** A page asked for before the saved one was read (Home's Songs tile). */
    private var requested: String? = null
    private var ready = false

    private val playlists = RowAdapter()
    private val artists = RowAdapter()
    private lateinit var songs: TrackAdapter
    private lateinit var songsHeader: ViewAdapter
    private val albums = AlbumAdapter { a -> main.openAlbum(a.id, a.title) }
    private var songList: List<Track> = emptyList()
    /** Asked once per visit, so a "no" isn't asked again right away. */
    private var askedThisTime = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentLibraryBinding.bind(view)
        this.b = b
        songs = TrackAdapter(
            onClick = { i, _ -> PlayerHub.play(main, songList, i) },
            onMore = { _, t -> SongActions.menu(main, t) },
        )
        songsHeader = ViewAdapter(R.layout.item_songs_header, 1) { v ->
            v.findViewById<TextView>(R.id.songsCount).text = Format.songsAndLength(songList.size, songList.sumOf { it.durationMs })
            v.findViewById<View>(R.id.songsShuffle).setOnClickListener { PlayerHub.play(main, songList, 0, shuffle = true) }
        }
        b.list.setHasFixedSize(true)
        b.newPlaylist.setOnClickListener { SongActions.newPlaylist(main) { id -> main.openDetail(DetailFragment.playlist(id)) } }
        b.settings.setOnClickListener { main.openSettings() }
        chips(b).forEach { (p, c) -> c.setOnClickListener { select(p) } }

        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { Settings.libraryPage }
            ready = true
            val want = requested
            requested = null
            select(want ?: saved, save = want != null)
        }
        whileStarted {
            launch { Changes.playlists.collect { if (ready && page == PLAYLISTS) loadPlaylists() } }
            launch { combine(Changes.downloads, Changes.local) { a, c -> a + c }.collect { if (ready && page != PLAYLISTS) loadLocal() } }
            launch { PlayerHub.state.collect { songs.setNow(it.track?.id) } }
        }
    }

    override fun onStart() {
        super.onStart()
        // Music files may have changed while away (a download finished, a file was copied).
        if (b != null && ready && page != PLAYLISTS) viewLifecycleOwner.lifecycleScope.launch { loadLocal() }
    }

    private val lifecycleScopeOrNull get() = if (view != null) viewLifecycleOwner.lifecycleScope else null

    private fun chips(b: FragmentLibraryBinding) =
        listOf(SONGS to b.chipSongs, PLAYLISTS to b.chipPlaylists, ALBUMS to b.chipAlbums, ARTISTS to b.chipArtists)

    /** Opens a page (Songs, Playlists, Albums or Artists), from Home's tiles for instance. */
    fun showPage(p: String) {
        if (b != null && ready) select(p) else requested = p
    }

    private fun select(p: String, save: Boolean = true) {
        val b = b ?: return
        page = if (p in PAGES) p else SONGS
        val i = page
        chips(b).forEach { (k, c) -> c.isSelected = k == i }
        // The last page picked comes back next time.
        if (save) lifecycleScopeOrNull?.launch(Dispatchers.IO) { Settings.libraryPage = i }
        b.empty.root.isVisible = false
        b.list.isVisible = true
        when (i) {
            PLAYLISTS -> {
                b.list.layoutManager = LinearLayoutManager(requireContext())
                b.list.adapter = playlists
            }
            SONGS -> {
                b.list.layoutManager = LinearLayoutManager(requireContext())
                val config = ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build()
                b.list.adapter = ConcatAdapter(config, songsHeader, songs)
            }
            ALBUMS -> {
                val span = if (resources.configuration.screenWidthDp >= 600) 4 else if (resources.configuration.screenWidthDp >= 420) 3 else 2
                b.list.layoutManager = GridLayoutManager(requireContext(), span)
                b.list.adapter = albums
            }
            else -> {
                b.list.layoutManager = LinearLayoutManager(requireContext())
                b.list.adapter = artists
            }
        }
        lifecycleScopeOrNull?.launch { if (i == PLAYLISTS) loadPlaylists() else loadLocal() }
    }

    private suspend fun loadPlaylists() {
        val lists = withContext(Dispatchers.IO) { Playlists.all() }
        val rows = ArrayList<Row>()
        rows += Row("loved", getString(R.string.loved_title), getString(R.string.lib_loved_sub), icon = R.drawable.ic_heart_filled, tile = R.color.accent) {
            main.openDetail(DetailFragment.loved())
        }
        rows += Row("downloads", getString(R.string.downloads_title), getString(R.string.lib_downloads_sub), icon = R.drawable.ic_download, tile = R.color.raised_2) {
            main.openDetail(DetailFragment.downloads())
        }
        rows += Row("import", getString(R.string.lib_import), getString(R.string.lib_import_sub), icon = R.drawable.ic_import, tile = R.color.raised_2) {
            main.openImport()
        }
        rows += Row("new", getString(R.string.new_playlist), getString(R.string.lib_new_playlist_sub), icon = R.drawable.ic_plus, tile = R.color.raised_2) {
            SongActions.newPlaylist(main) { id -> main.openDetail(DetailFragment.playlist(id)) }
        }
        rows += lists.map(::row)
        playlists.submitList(rows)
    }

    private fun row(p: Playlist) = Row(
        key = "p${p.id}",
        title = p.name,
        sub = if (p.site.isNotEmpty()) {
            getString(R.string.lib_imported_sub, p.site, Format.plural(p.count, "song", "songs"))
        } else {
            getString(R.string.lib_playlist_sub, Format.plural(p.count, "song", "songs"))
        },
        art = p.cover.ifEmpty { p.firstArt },
    ) { main.openDetail(DetailFragment.playlist(p.id)) }

    private suspend fun loadLocal() {
        val b = b ?: return
        val ctx = requireContext().applicationContext
        if (!LocalMusic.hasPermission(ctx)) {
            showNoAccess()
            return
        }
        val which = page
        when (which) {
            SONGS -> {
                val list = withContext(Dispatchers.IO) { LocalMusic.songs(ctx) }
                if (page != which) return
                songList = list
                songs.submitList(list)
                songsHeader.refresh()
                songs.setDownloads(Downloads.states.value)
                if (list.isEmpty()) b.empty.show(R.drawable.ic_music, getString(R.string.lib_no_songs_title), getString(R.string.lib_no_songs_body), getString(R.string.home_empty_button)) { main.openSearch() }
                else b.empty.root.isVisible = false
                b.list.isVisible = list.isNotEmpty()
            }
            ALBUMS -> {
                val list = withContext(Dispatchers.IO) { LocalMusic.albums(ctx) }
                if (page != which) return
                albums.submitList(list)
                if (list.isEmpty()) b.empty.show(R.drawable.ic_album, getString(R.string.lib_no_albums_title), getString(R.string.lib_no_albums_body))
                else b.empty.root.isVisible = false
                b.list.isVisible = list.isNotEmpty()
            }
            ARTISTS -> {
                val list = withContext(Dispatchers.IO) { LocalMusic.artists(ctx) }
                if (page != which) return
                artists.submitList(
                    list.map { a ->
                        Row(
                            key = "a${a.id}",
                            title = a.name,
                            sub = Format.plural(a.songs, "song", "songs"),
                            circle = true,
                            placeholder = R.drawable.art_placeholder_artist,
                        ) { main.openDetail(DetailFragment.artist(a.id, a.name)) }
                    },
                )
                if (list.isEmpty()) b.empty.show(R.drawable.ic_artist, getString(R.string.lib_no_artists_title), getString(R.string.lib_no_artists_body))
                else b.empty.root.isVisible = false
                b.list.isVisible = list.isNotEmpty()
            }
        }
    }

    private fun showNoAccess() {
        val b = b ?: return
        b.list.isVisible = false
        val permanentlyDenied = askedThisTime && !shouldShowRequestPermissionRationale(LocalMusic.permission)
        if (permanentlyDenied) {
            b.empty.show(R.drawable.ic_folder, getString(R.string.lib_no_access_title), getString(R.string.lib_no_access_body), getString(R.string.lib_no_access_settings)) {
                startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)),
                )
            }
        } else {
            b.empty.show(R.drawable.ic_folder, getString(R.string.lib_no_access_title), getString(R.string.lib_no_access_body), getString(R.string.lib_no_access_button)) {
                askedThisTime = true
                main.askAudio(LocalMusic.permission) { ok ->
                    lifecycleScopeOrNull?.launch { if (ok) { b.list.isVisible = true; loadLocal() } else showNoAccess() }
                }
            }
        }
    }

    companion object {
        const val SONGS = "songs"
        const val PLAYLISTS = "playlists"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        private val PAGES = setOf(SONGS, PLAYLISTS, ALBUMS, ARTISTS)
    }

    override fun scrollToTop() {
        b?.list?.smoothScrollToPosition(0)
    }

    override fun onDestroyView() {
        b?.list?.adapter = null
        b = null
        super.onDestroyView()
    }

    /** Album covers in a grid. */
    class AlbumAdapter(private val onClick: (LocalMusic.Album) -> Unit) :
        ListAdapter<LocalMusic.Album, AlbumAdapter.Holder>(Diff) {
        private var px = 0

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = getItem(position).id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            if (px == 0) px = parent.context.dp(200)
            val h = Holder(b)
            b.root.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(getItem(p))
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val a = getItem(position)
            h.b.albumTitle.text = a.title
            h.b.albumSub.text = a.artist
            Images.url(h.b.albumArt, a.art)
        }

        class Holder(val b: ItemAlbumBinding) : RecyclerView.ViewHolder(b.root)

        object Diff : DiffUtil.ItemCallback<LocalMusic.Album>() {
            override fun areItemsTheSame(a: LocalMusic.Album, b: LocalMusic.Album) = a.id == b.id
            override fun areContentsTheSame(a: LocalMusic.Album, b: LocalMusic.Album) = a == b
        }
    }
}
