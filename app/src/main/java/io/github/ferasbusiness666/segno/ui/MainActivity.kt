package io.github.ferasbusiness666.segno.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.ActivityMainBinding
import io.github.ferasbusiness666.segno.spotify.SpotifyParser
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.NowState
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.setTextIfChanged
import io.github.ferasbusiness666.segno.ui.detail.DetailFragment
import io.github.ferasbusiness666.segno.ui.home.HomeFragment
import io.github.ferasbusiness666.segno.ui.importer.ImportFragment
import io.github.ferasbusiness666.segno.ui.library.LibraryFragment
import io.github.ferasbusiness666.segno.ui.nowplaying.NowPlayingFragment
import io.github.ferasbusiness666.segno.ui.search.SearchFragment
import io.github.ferasbusiness666.segno.ui.settings.SettingsFragment
import io.github.ferasbusiness666.segno.update.Updates
import io.github.ferasbusiness666.segno.youtube.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one activity: three tabs (Home, Search, Library), screens opened on top of them, the mini
 * player, and Now Playing over everything.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var tab = 0
    private var navBottom = 0
    private var imeOpen = false
    private var lastTrackId: String? = null
    private val tabViews by lazy { listOf(b.tabHome, b.tabSearch, b.tabLibrary) }

    /** Screens that start a tab. */
    interface Root {
        fun scrollToTop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupInsets()

        tab = savedInstanceState?.getInt(KEY_TAB) ?: 0
        if (savedInstanceState == null) {
            showTab(0)
        } else {
            // The screens come back by themselves after a rotation; only the tab bar needs setting.
            tabViews.forEachIndexed { k, v -> v.isSelected = k == tab }
        }
        tabViews.forEachIndexed { i, v -> v.setOnClickListener { onTabClick(i) } }

        b.mini.miniCard.setOnClickListener { openNowPlaying() }
        b.mini.miniPlay.setOnClickListener { PlayerHub.toggle() }
        b.mini.miniNext.setOnClickListener { PlayerHub.next() }

        supportFragmentManager.addOnBackStackChangedListener { onStackChanged() }
        onBackPressedDispatcher.addCallback(this, backToHome)
        onStackChanged()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlayerHub.state.collect(::renderMini) }
                launch { Messages.flow.collect(::toast) }
                launch {
                    while (true) {
                        updateMiniProgress()
                        delay(1000)
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)

        // The update check waits a little, so it never slows down opening the app.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(4000)
            runCatching { Updates.checkIfDue() }
        }
    }

    override fun onStart() {
        super.onStart()
        // Connect to the player after the first frame is drawn.
        b.root.post { PlayerHub.connect(this) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) PlayerHub.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_NOW_PLAYING -> b.root.post { openNowPlaying() }
            ACTION_DOWNLOADS -> openDetail(DetailFragment.downloads())
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) openShared(text)
            }
        }
    }

    /** A link or words shared from another app. */
    private fun openShared(text: String) {
        val playlistLike = text.split(Regex("\\s+")).any { word ->
            (SpotifyParser.isSpotify(word) && !word.contains("/track/") && !word.startsWith("spotify:track")) ||
                Links.playlistId(word) != null && Links.videoId(word) == null ||
                Links.channelPlaylists(word) != null
        }
        if (playlistLike) openImport(text) else openSearch(text)
    }

    // Insets ---------------------------------------------------------------------------------------

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeOpen = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > bars.bottom
            navBottom = bars.bottom
            // Side bars (landscape) and the keyboard: the main area moves out of their way.
            b.main.updatePadding(left = bars.left, right = bars.right, bottom = if (imeOpen) ime.bottom else 0)
            b.content.updatePadding(top = bars.top)
            b.tabs.updatePadding(bottom = bars.bottom)
            b.bottom.isVisible = !imeOpen
            placeToast()
            insets
        }
    }

    private fun placeToast() {
        b.toast.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = when {
                isNowPlayingOpen() -> navBottom + dp(96)
                imeOpen -> b.main.paddingBottom + dp(12)
                else -> (if (b.bottom.height > 0) b.bottom.height else dp(64) + navBottom) + dp(12)
            }
        }
    }

    // Tabs and screens -----------------------------------------------------------------------------

    private fun onTabClick(i: Int) {
        val fm = supportFragmentManager
        if (i == tab && fm.backStackEntryCount == 0) {
            (fm.findFragmentByTag(TAGS[i]) as? Root)?.scrollToTop()
            return
        }
        showTab(i)
    }

    /** Shows tab [i], closing any screens opened on top. */
    fun showTab(i: Int) {
        val fm = supportFragmentManager
        if (fm.backStackEntryCount > 0) fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        tab = i
        val t = fm.beginTransaction().setReorderingAllowed(true)
        TAGS.forEachIndexed { k, tag ->
            val f = fm.findFragmentByTag(tag)
            if (k == i) {
                if (f == null) t.add(R.id.content, newRoot(k), tag) else t.show(f)
            } else if (f != null && !f.isHidden) {
                t.hide(f)
            }
        }
        t.commitNow()
        tabViews.forEachIndexed { k, v -> v.isSelected = k == i }
        backToHome.isEnabled = tab != 0 && fm.backStackEntryCount == 0
    }

    private fun newRoot(k: Int): Fragment = when (k) {
        0 -> HomeFragment()
        1 -> SearchFragment()
        else -> LibraryFragment()
    }

    private fun currentScreen(): Fragment? =
        supportFragmentManager.fragments.lastOrNull { it.id == R.id.content && it.isAdded && !it.isHidden }

    /** Opens a screen on top of the current one. */
    fun push(f: Fragment) {
        if (isNowPlayingOpen()) supportFragmentManager.popBackStackImmediate(NP, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val t = supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.fade_in, R.anim.none, R.anim.none, R.anim.fade_out)
        currentScreen()?.let { t.hide(it) }
        t.add(R.id.content, f).addToBackStack(null).commit()
    }

    fun openDetail(f: DetailFragment) = push(f)

    fun openAlbum(id: Long, title: String) = push(DetailFragment.album(id, title))

    fun openImport(text: String? = null) = push(ImportFragment.newInstance(text))

    fun openSettings() = push(SettingsFragment())

    fun openSearch(query: String? = null) {
        showTab(1)
        if (query != null) {
            (supportFragmentManager.findFragmentByTag(TAGS[1]) as? SearchFragment)?.searchFor(query)
        }
    }

    /** Shows Library, on [page] if given (LibraryFragment.SONGS, PLAYLISTS...). */
    fun openLibrary(page: String? = null) {
        showTab(2)
        if (page != null) (supportFragmentManager.findFragmentByTag(TAGS[2]) as? LibraryFragment)?.showPage(page)
    }

    fun isNowPlayingOpen() = supportFragmentManager.findFragmentByTag(NP) != null

    fun openNowPlaying() {
        if (isNowPlayingOpen() || isFinishing) return
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.slide_up, R.anim.none, R.anim.none, R.anim.slide_down)
            .add(R.id.overlay, NowPlayingFragment(), NP)
            .addToBackStack(NP)
            .commit()
    }

    private fun onStackChanged() {
        val np = isNowPlayingOpen()
        // Nothing behind Now Playing needs drawing once it covers the screen.
        if (np) {
            b.main.postDelayed({ if (isNowPlayingOpen()) b.main.visibility = View.INVISIBLE }, 260)
        } else {
            b.main.visibility = View.VISIBLE
        }
        backToHome.isEnabled = tab != 0 && supportFragmentManager.backStackEntryCount == 0
        placeToast()
    }

    /** Back on Search or Library goes to Home first. */
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showTab(0)
    }

    // Mini player ----------------------------------------------------------------------------------

    private fun renderMini(s: NowState) {
        val t = s.track
        val m = b.mini
        m.root.isVisible = t != null
        if (t == null) return
        m.miniTitle.setTextIfChanged(t.title)
        m.miniArtist.setTextIfChanged(t.artist.ifEmpty { getString(R.string.unknown_artist) })
        if (t.id != lastTrackId || m.miniArt.drawable == null) {
            lastTrackId = t.id
            Images.track(m.miniArt, t, dp(80))
        }
        val playing = s.showPause
        m.miniPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        m.miniPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        m.miniNext.isEnabled = s.hasNext
        m.miniNext.alpha = if (s.hasNext) 1f else 0.4f
        updateMiniProgress()
        b.bottom.post { placeToast() }
    }

    private fun updateMiniProgress() {
        val s = PlayerHub.state.value
        if (s.track == null || s.durationMs <= 0) {
            b.mini.miniProgress.progress = 0
            return
        }
        b.mini.miniProgress.progress = (PlayerHub.position() * 1000 / s.durationMs).toInt().coerceIn(0, 1000)
    }

    // Toasts ---------------------------------------------------------------------------------------

    private val hideToast = Runnable {
        b.toast.animate().alpha(0f).setDuration(150).withEndAction { b.toast.visibility = View.GONE }.start()
    }

    fun toast(text: String) {
        placeToast()
        b.toast.removeCallbacks(hideToast)
        b.toast.text = text
        if (!b.toast.isVisible) {
            b.toast.alpha = 0f
            b.toast.visibility = View.VISIBLE
        }
        b.toast.animate().alpha(1f).setDuration(150).start()
        b.toast.postDelayed(hideToast, if (text.length > 60) 4500L else 2500L)
    }

    // Permissions ----------------------------------------------------------------------------------

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast(getString(R.string.perm_notif_denied))
    }

    /** Asks once (Android 13+) for notifications, used for download progress. */
    fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || Settings.askedNotifications) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Settings.askedNotifications = true
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private var afterStorage: (() -> Unit)? = null
    private val storageLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val run = afterStorage
        afterStorage = null
        if (ok) run?.invoke()
    }

    /** Android 8 and 9 need permission to save into Music/Segno. */
    fun askStorage(then: () -> Unit) {
        afterStorage = then
        storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private var afterSystem: ((Boolean) -> Unit)? = null
    private val systemLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val run = afterSystem
        afterSystem = null
        run?.invoke(r.resultCode == Activity.RESULT_OK)
    }

    /** Shows a system dialog (such as Android's "Delete this file?") and reports whether it was allowed. */
    fun askSystem(sender: IntentSender, then: (Boolean) -> Unit) {
        afterSystem = then
        systemLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    private var afterAudio: ((Boolean) -> Unit)? = null
    private val audioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val run = afterAudio
        afterAudio = null
        run?.invoke(ok)
    }

    fun askAudio(permission: String, then: (Boolean) -> Unit) {
        afterAudio = then
        audioLauncher.launch(permission)
    }

    companion object {
        const val ACTION_NOW_PLAYING = "io.github.ferasbusiness666.segno.NOW_PLAYING"
        const val ACTION_DOWNLOADS = "io.github.ferasbusiness666.segno.DOWNLOADS"
        private const val KEY_TAB = "tab"
        private const val NP = "now_playing"
        private val TAGS = listOf("home", "search", "library")
    }
}
