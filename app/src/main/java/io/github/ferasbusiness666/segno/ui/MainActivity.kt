package io.github.ferasbusiness666.segno.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ferasbusiness666.segno.BuildConfig
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.backup.Backup
import io.github.ferasbusiness666.segno.backup.BackupFormat
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.ActivityMainBinding
import io.github.ferasbusiness666.segno.library.LocalMusic
import io.github.ferasbusiness666.segno.spotify.SpotifyParser
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.NowState
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.openUrl
import io.github.ferasbusiness666.segno.ui.common.setIcon
import io.github.ferasbusiness666.segno.ui.common.setTextIfChanged
import io.github.ferasbusiness666.segno.ui.detail.DetailFragment
import io.github.ferasbusiness666.segno.ui.home.HomeFragment
import io.github.ferasbusiness666.segno.ui.importer.ImportFragment
import io.github.ferasbusiness666.segno.ui.library.LibraryFragment
import io.github.ferasbusiness666.segno.ui.nowplaying.NowPlayingFragment
import io.github.ferasbusiness666.segno.ui.search.SearchFragment
import io.github.ferasbusiness666.segno.ui.settings.SettingsFragment
import io.github.ferasbusiness666.segno.update.SelfUpdate
import io.github.ferasbusiness666.segno.update.Updates
import io.github.ferasbusiness666.segno.youtube.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                    // Android's "Install this update?" question, shown while Segno is on screen.
                    SelfUpdate.confirm.collect { ask ->
                        if (ask != null) {
                            SelfUpdate.confirmShown()
                            runCatching { startActivity(ask) }.onFailure { SelfUpdate.finished(getString(R.string.upd_install_failed)) }
                        }
                    }
                }
                launch {
                    // The mini player's thin progress line: once a second, only while playing.
                    while (true) {
                        if (PlayerHub.state.value.isPlaying) updateMiniProgress()
                        delay(1000)
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)

        // The update check waits a little, so it never slows down opening the app.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(4000)
            runCatching { SelfUpdate.cleanUp(applicationContext) }
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
            ACTION_TEST_UPDATE -> if (BuildConfig.UPDATE_TEST_HOOK) testUpdate(intent)
            ACTION_TEST_BACKUP -> if (BuildConfig.DEBUG) testBackup(intent)
        }
    }

    // Backup ---------------------------------------------------------------------------------------

    /** Saves the backup to [uri] (picked in the system's "save as" screen). */
    fun saveBackup(uri: Uri) {
        val app = applicationContext
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backup.save(app, uri) } }
            r.onSuccess {
                toast(
                    getString(
                        R.string.backup_saved,
                        resources.getQuantityString(R.plurals.backup_playlists, it.playlists, it.playlists),
                        resources.getQuantityString(R.plurals.backup_loved, it.loved, it.loved),
                    ),
                )
            }.onFailure {
                android.util.Log.w("Segno", "Couldn't save the backup", it)
                toast(getString(R.string.backup_save_failed))
            }
        }
    }

    /** Restores the backup at [uri]. Asks for the music files first, so they can be matched. */
    fun restoreBackup(uri: Uri) {
        if (LocalMusic.hasPermission(this)) restoreNow(uri) else askAudio(LocalMusic.permission) { restoreNow(uri) }
    }

    private fun restoreNow(uri: Uri) {
        val app = applicationContext
        toast(getString(R.string.backup_restoring))
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backup.restore(app, uri) } }
            r.onSuccess { done ->
                done.queue?.let { q ->
                    PlayerHub.restoreQueue(this@MainActivity, done.queueTracks, q.index, q.positionMs, q.shuffle, BackupFormat.repeatMode(q.repeat))
                }
                var text = getString(
                    R.string.backup_restored,
                    resources.getQuantityString(R.plurals.backup_playlists, done.playlists, done.playlists),
                    resources.getQuantityString(R.plurals.backup_loved, done.lovedAdded, done.lovedAdded),
                )
                if (done.missing > 0) {
                    text += "\n\n" + getString(R.string.backup_restored_missing, resources.getQuantityString(R.plurals.backup_songs, done.missing, done.missing))
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.backup_restored_title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { e ->
                android.util.Log.w("Segno", "Couldn't restore the backup", e)
                val why = when ((e as? BackupFormat.Unreadable)?.reason) {
                    BackupFormat.Reason.NotABackup -> R.string.backup_not_backup
                    BackupFormat.Reason.Newer -> R.string.backup_newer
                    BackupFormat.Reason.Damaged -> R.string.backup_damaged
                    null -> R.string.backup_read_failed
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.backup_failed_title)
                    .setMessage(why)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** CI only (debug builds): saves or restores a backup file in Segno's files folder, without the file picker. */
    private fun testBackup(intent: Intent) {
        val dir = getExternalFilesDir(null) ?: return
        val file = File(dir, File(intent.getStringExtra("file").orEmpty().ifEmpty { "backup.json" }).name)
        when (intent.getStringExtra("mode")) {
            "save" -> saveBackup(Uri.fromFile(file))
            "restore" -> restoreBackup(Uri.fromFile(file))
        }
    }

    // Updates --------------------------------------------------------------------------------------

    /** Asks about a new version: "Segno 1.0.1 is out", a few lines of notes, Update or Later. */
    fun offerUpdate(r: Updates.Release, local: File? = null) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_banner, r.version))
            .setMessage(r.notes.ifEmpty { getString(R.string.update_no_notes) })
            .setPositiveButton(if (SelfUpdate.canInstall) R.string.update_get else R.string.update_open_page) { _, _ -> startUpdate(r, local) }
            .setNegativeButton(R.string.update_dismiss) { _, _ -> Updates.dismiss() }
            .show()
    }

    /** Update: downloads and installs it (release builds), or opens its page (debug builds, or no APK). */
    fun startUpdate(r: Updates.Release, local: File? = null) {
        if (!SelfUpdate.canInstall || (r.apkUrl.isEmpty() && local == null)) {
            openUrl(r.pageUrl)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdate = r to local
            if (!Settings.explainedInstalls) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.upd_allow_title)
                    .setMessage(R.string.upd_allow_body)
                    .setPositiveButton(R.string.upd_allow_go) { _, _ ->
                        Settings.explainedInstalls = true
                        openInstallSettings()
                    }
                    .setNegativeButton(R.string.update_dismiss) { _, _ -> pendingUpdate = null }
                    .show()
            } else {
                toast(getString(R.string.upd_allow_again))
                openInstallSettings()
            }
            return
        }
        SelfUpdate.start(this, r, local)
    }

    private var pendingUpdate: Pair<Updates.Release, File?>? = null

    private val installSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Back from Android's "Install unknown apps" page: carry on if it's allowed now.
        val p = pendingUpdate ?: return@registerForActivityResult
        pendingUpdate = null
        if (packageManager.canRequestPackageInstalls()) SelfUpdate.start(this, p.first, p.second) else toast(getString(R.string.upd_not_allowed))
    }

    private fun openInstallSettings() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        runCatching { installSettings.launch(intent) }.onFailure { pendingUpdate = null; openUrl(Updates.RELEASES_PAGE) }
    }

    /** CI only (builds without the real signing key): offers an APK the test copied into Segno's files folder. */
    private fun testUpdate(intent: Intent) {
        val name = File(intent.getStringExtra("apk").orEmpty()).name
        val dir = getExternalFilesDir(null) ?: return
        val file = File(dir, name)
        if (name.isEmpty() || !file.isFile) {
            toast("Test update: no file $name")
            return
        }
        val r = Updates.Release(
            version = intent.getStringExtra("version").orEmpty().ifEmpty { "0.0.0" },
            pageUrl = Updates.RELEASES_PAGE,
            apkName = name,
            notes = "• A test update made by CI.\n• Checks the package, the signature and the version.",
        )
        offerUpdate(r, file)
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
        val margin = when {
            isNowPlayingOpen() -> navBottom + dp(96)
            imeOpen -> b.main.paddingBottom + dp(12)
            else -> (if (b.bottom.height > 0) b.bottom.height else dp(64) + navBottom) + dp(12)
        }
        // Only when it moves: setting layout params lays the whole window out again.
        val lp = b.toast.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.bottomMargin != margin) b.toast.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = margin }
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
        m.miniPlay.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
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
        const val ACTION_TEST_UPDATE = "io.github.ferasbusiness666.segno.TEST_UPDATE"
        const val ACTION_TEST_BACKUP = "io.github.ferasbusiness666.segno.TEST_BACKUP"
        private const val KEY_TAB = "tab"
        private const val NP = "now_playing"
        private val TAGS = listOf("home", "search", "library")
    }
}
