package io.github.ferasbusiness666.segno.ui.search

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.FragmentSearchBinding
import io.github.ferasbusiness666.segno.databinding.ItemTipBinding
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.SongActions
import io.github.ferasbusiness666.segno.ui.common.TrackAdapter
import io.github.ferasbusiness666.segno.ui.common.show
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import io.github.ferasbusiness666.segno.youtube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search YouTube (YouTube Music songs first), or paste a link. */
class SearchFragment : Fragment(R.layout.fragment_search), MainActivity.Root {
    private var b: FragmentSearchBinding? = null
    private val vm: SearchViewModel by viewModels()
    private val main get() = requireActivity() as MainActivity
    private lateinit var adapter: TrackAdapter
    private var pendingQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.loadFilter()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSearchBinding.bind(view)
        this.b = b
        adapter = TrackAdapter(
            onClick = { _, t ->
                hideKeyboard()
                // Plays the song tapped; when it ends, songs like it follow (see PlaybackService).
                PlayerHub.play(main, listOf(t), 0)
                main.askNotifications()
            },
            onMore = { _, t -> SongActions.menu(main, t) },
        )
        b.results.layoutManager = LinearLayoutManager(requireContext())
        b.results.setHasFixedSize(true)
        b.results.adapter = adapter
        b.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) vm.loadMore()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        b.query.setOnEditorActionListener { v, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(v.text.toString())
                true
            } else {
                false
            }
        }
        b.query.doAfterTextChanged {
            b.clear.isVisible = !it.isNullOrEmpty()
            if (it.isNullOrEmpty()) vm.clear()
        }
        b.clear.setOnClickListener {
            b.query.setText("")
            b.query.requestFocus()
            showKeyboard()
        }
        b.chipSongs.setOnClickListener { setFilter(YouTube.SONGS) }
        b.chipVideos.setOnClickListener { setFilter(YouTube.VIDEOS) }
        b.clearRecent.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { Settings.recentSearches = emptyList() }
                renderRecent(emptyList())
            }
        }
        addTips(b)

        whileStarted {
            launch { vm.state.collect(::render) }
            launch { vm.filterFlow.collect { renderChips() } }
            launch { vm.openImport.collect { main.openImport(it) } }
            launch { PlayerHub.state.collect { adapter.setNow(it.track?.id) } }
            launch { Downloads.states.collect { adapter.setDownloads(it) } }
        }
        pendingQuery?.let {
            pendingQuery = null
            searchFor(it)
        }
    }

    override fun onStart() {
        super.onStart()
        if (b == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) { Settings.recentSearches }
            renderRecent(recent)
        }
    }

    /** Fills the box and searches (from a shared link or another screen). */
    fun searchFor(query: String) {
        val b = b
        if (b == null) {
            pendingQuery = query
            return
        }
        b.query.setText(query)
        b.query.setSelection(query.length)
        submit(query)
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        hideKeyboard()
        vm.search(text)
    }

    private fun setFilter(f: String) {
        vm.setFilter(f)
        renderChips()
    }

    private fun renderChips() {
        val b = b ?: return
        b.chipSongs.isSelected = vm.filter == YouTube.SONGS
        b.chipVideos.isSelected = vm.filter == YouTube.VIDEOS
    }

    private fun render(s: SearchViewModel.State) {
        val b = b ?: return
        b.idle.isVisible = s is SearchViewModel.State.Idle
        b.loading.isVisible = s is SearchViewModel.State.Loading
        b.results.isVisible = s is SearchViewModel.State.Results
        b.empty.root.isVisible = false
        when (s) {
            is SearchViewModel.State.Results -> adapter.submitList(s.tracks)
            is SearchViewModel.State.Empty -> b.empty.show(R.drawable.ic_search, getString(R.string.search_no_results), getString(R.string.search_tip1_body))
            is SearchViewModel.State.Failed -> when {
                s.offline -> b.empty.show(R.drawable.ic_offline, getString(R.string.search_offline), getString(R.string.search_offline_body), getString(R.string.try_again)) { vm.retry() }
                else -> b.empty.show(
                    R.drawable.ic_alert,
                    getString(if (s.link) R.string.search_link_failed else R.string.search_failed),
                    s.message,
                    getString(R.string.try_again),
                ) { vm.retry() }
            }
            is SearchViewModel.State.Loading -> adapter.submitList(emptyList())
            SearchViewModel.State.Idle -> adapter.submitList(emptyList())
        }
    }

    private fun renderRecent(list: List<String>) {
        val b = b ?: return
        b.recentHeader.isVisible = list.isNotEmpty()
        b.recentList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (q in list.take(6)) {
            val row = inflater.inflate(R.layout.item_recent_search, b.recentList, false) as TextView
            row.text = q
            row.setOnClickListener { searchFor(q) }
            b.recentList.addView(row)
        }
    }

    private fun addTips(b: FragmentSearchBinding) {
        val tips = listOf(
            Triple(R.drawable.ic_search, R.string.search_tip1_title, R.string.search_tip1_body),
            Triple(R.drawable.ic_link, R.string.search_tip2_title, R.string.search_tip2_body),
            Triple(R.drawable.ic_download, R.string.search_tip3_title, R.string.search_tip3_body),
        )
        val inflater = LayoutInflater.from(requireContext())
        for ((icon, title, body) in tips) {
            val t = ItemTipBinding.inflate(inflater, b.tips, false)
            t.tipIcon.setImageResource(icon)
            t.tipTitle.setText(title)
            t.tipBody.setText(body)
            b.tips.addView(t.root)
        }
    }

    private fun hideKeyboard() {
        val b = b ?: return
        requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(b.query.windowToken, 0)
        b.query.clearFocus()
    }

    private fun showKeyboard() {
        val b = b ?: return
        requireContext().getSystemService<InputMethodManager>()?.showSoftInput(b.query, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun scrollToTop() {
        val b = b ?: return
        if (b.results.isVisible && (b.results.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() > 0) {
            b.results.smoothScrollToPosition(0)
        } else {
            b.query.requestFocus()
            showKeyboard()
        }
    }

    override fun onDestroyView() {
        b?.results?.adapter = null
        b = null
        super.onDestroyView()
    }
}
