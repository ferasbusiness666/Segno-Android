package io.github.ferasbusiness666.segno.ui.nowplaying

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import coil3.load
import coil3.request.allowHardware
import coil3.request.error
import coil3.request.placeholder
import coil3.toBitmap
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Changes
import io.github.ferasbusiness666.segno.data.Loved
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.FragmentNowPlayingBinding
import io.github.ferasbusiness666.segno.download.DownloadState
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.lyrics.LyricsCenter
import io.github.ferasbusiness666.segno.lyrics.LyricsState
import io.github.ferasbusiness666.segno.playback.MediaItems
import io.github.ferasbusiness666.segno.playback.Sleep
import io.github.ferasbusiness666.segno.playback.SleepTimer
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.NowState
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.SongActions
import io.github.ferasbusiness666.segno.ui.common.clearIcon
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.setIcon
import io.github.ferasbusiness666.segno.ui.common.setTextIfChanged
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Now Playing: a big cover, the song, a seek bar and the controls, then love, download, sleep
 * timer and queue. A still cover with a soft shadow (no spinning record), so weak phones stay cool.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing) {
    private var b: FragmentNowPlayingBinding? = null
    private val main get() = requireActivity() as MainActivity
    private var artId: String? = null
    private var dragging = false
    private var loved = false
    private var state = NowState()
    private var sleepPopup: SleepPopup? = null
    private var lyricsPanel: LyricsPanel? = null
    private var lyricsJob: Job? = null
    private var lyricsRetry: Job? = null
    /** Lyrics show instead of the cover (and only then are they looked up). */
    private var lyricsOpen = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentNowPlayingBinding.bind(view)
        this.b = b
        insets(b)
        b.top.npClose.setOnClickListener { parentFragmentManager.popBackStack() }
        b.top.npMore.setOnClickListener { state.track?.let { SongActions.menu(main, it) } }
        b.npArt.setOnClickListener { PlayerHub.toggle() }
        b.controls.npPlay.setOnClickListener { PlayerHub.toggle() }
        b.controls.npPrev.setOnClickListener { PlayerHub.previous() }
        b.controls.npNext.setOnClickListener { PlayerHub.next() }
        b.controls.npShuffle.setOnClickListener { PlayerHub.setShuffle(!state.shuffle) }
        b.controls.npRepeat.setOnClickListener { PlayerHub.cycleRepeat() }
        b.actions.npLove.setOnClickListener {
            val t = state.track ?: return@setOnClickListener
            SongActions.toggleLove(main, t)
        }
        b.actions.npDownload.setOnClickListener { onDownload() }
        b.actions.npSleep.setOnClickListener { openSleep() }
        lyricsPanel = LyricsPanel(
            b.lyrics,
            onSeek = { ms -> PlayerHub.seekTo(ms) },
            onRetry = { askLyrics(force = true) },
            onWrong = { key -> askLyrics(force = true, avoid = key) },
            onNudge = { by ->
                val app = main.applicationContext
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { LyricsCenter.nudge(app, by) }
            },
        )
        b.actions.npLyrics.setOnClickListener { setLyricsOpen(!lyricsOpen, save = true) }
        val lyricsOn = Settings.syncedLyrics
        b.actions.npLyrics.isVisible = lyricsOn
        b.actions.npLyricsGap.isVisible = lyricsOn
        setLyricsOpen(lyricsOn && Settings.lyricsOpen, save = false)
        b.actions.npQueue.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("queue") == null) QueueSheet().show(childFragmentManager, "queue")
        }
        b.seek.npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && state.durationMs > 0) showTimes(progress * state.durationMs / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                if (state.durationMs > 0) PlayerHub.seekTo(sb.progress * state.durationMs / 1000)
            }
        })

        whileStarted {
            launch { PlayerHub.state.collect(::render) }
            launch { Downloads.states.collect { renderDownload() } }
            launch { Changes.loved.collect { refreshLoved() } }
            launch { LyricsCenter.shown.collect(::renderLyrics) }
            launch {
                // Position and sleep countdown, only while this screen shows: once a second, on
                // the song's next whole second, so the time changes once and on time.
                while (true) {
                    tick()
                    val pos = PlayerHub.position()
                    delay(if (state.isPlaying) (1000 - pos % 1000).coerceIn(250, 1000) else 1000)
                }
            }
        }
    }

    private fun insets(b: FragmentNowPlayingBinding) {
        ViewCompat.setOnApplyWindowInsetsListener(b.npRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.top.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            b.actions.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + b.root.dp(12) }
            b.npRoot.setPadding(bars.left, 0, bars.right, 0)
            insets
        }
        ViewCompat.requestApplyInsets(b.npRoot)
    }

    private fun render(s: NowState) {
        val b = b ?: return
        val old = state
        state = s
        val t = s.track
        if (t == null) {
            b.npTitle.setTextIfChanged(getString(R.string.np_nothing_title))
            b.npArtist.setTextIfChanged(getString(R.string.np_nothing_body))
            b.top.npSource.text = ""
            Images.placeholder(b.npArt)
            setControlsEnabled(false)
            return
        }
        setControlsEnabled(true)
        b.npTitle.setTextIfChanged(t.title)
        b.npArtist.setTextIfChanged(t.artist.ifEmpty { getString(R.string.unknown_artist) })
        b.top.npSource.setTextIfChanged(
            getString(
                when {
                    Downloads.state(t.id)?.isDone == true -> R.string.np_downloaded
                    t.kind == Track.Kind.Spotify -> R.string.np_from_spotify
                    t.kind == Track.Kind.YouTube -> R.string.np_from_youtube
                    else -> R.string.np_from_phone
                },
            ),
        )
        val artKey = t.id + "|" + t.art + "|" + t.video
        if (artKey != artId) {
            artId = artKey
            loadArt(t)
        }
        if (old.track?.id != t.id) {
            refreshLoved()
            if (lyricsOpen) askLyrics()
        }

        val playing = s.showPause
        b.controls.npPlay.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.controls.npPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        b.npArt.contentDescription = b.controls.npPlay.contentDescription
        b.controls.npBuffering.isVisible = s.buffering && s.playWhenReady
        b.controls.npNext.alpha = if (s.hasNext) 1f else 0.4f
        b.controls.npNext.isEnabled = s.hasNext

        b.controls.npShuffle.setIcon(R.drawable.ic_shuffle, if (s.shuffle) R.color.accent else R.color.text_2)
        b.controls.npShuffle.contentDescription = getString(if (s.shuffle) R.string.shuffle_on else R.string.shuffle)
        b.controls.npRepeat.setIcon(
            if (s.repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            if (s.repeat != Player.REPEAT_MODE_OFF) R.color.accent else R.color.text_2,
        )
        b.controls.npRepeat.contentDescription = getString(
            when (s.repeat) {
                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                else -> R.string.repeat
            },
        )
        renderDownload()
        if (s.sleep != old.sleep || old.track == null) renderSleep()
        tick()
        sleepPopup?.render(s.sleep)
    }

    private fun setControlsEnabled(on: Boolean) {
        val b = b ?: return
        listOf(b.controls.npPlay, b.controls.npPrev, b.actions.npLove, b.actions.npDownload, b.top.npMore, b.seek.npSeek).forEach {
            it.isEnabled = on
            it.alpha = if (on) 1f else 0.4f
        }
    }

    private fun loadArt(t: Track) {
        val b = b ?: return
        val url = MediaItems.artFor(t, 720)
        if (url.isEmpty()) {
            Images.placeholder(b.npArt)
            wash(null)
            return
        }
        b.npArt.load(url) {
            val ph = Images.placeholderDrawable(b.npArt)
            placeholder(ph)
            error(ph)
            // A readable bitmap, so the background can take the cover's color.
            allowHardware(false)
            listener(
                onSuccess = { _, result -> wash(result.image.toBitmap()) },
                onError = { _, _ -> wash(null) },
            )
        }
    }

    /**
     * The cover's color washes the top of the screen, calm enough for white text. The color is
     * worked out off the main thread, so it never holds up the slide-in of this screen.
     */
    private fun wash(bitmap: Bitmap?) {
        if (bitmap == null) {
            paintWash(Color.rgb(0x4A, 0x3A, 0x5E))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paintWash(withContext(Dispatchers.Default) { averageColor(bitmap) })
        }
    }

    private fun paintWash(color: Int) {
        val b = b ?: return
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * 0.9f).coerceAtMost(0.62f)
        hsl[2] = hsl[2].coerceIn(0.22f, 0.34f)
        val top = ColorUtils.HSLToColor(hsl)
        val bg = ContextCompat.getColor(requireContext(), R.color.bg)
        b.wash.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, ColorUtils.blendARGB(top, bg, 0.55f), bg))
    }

    private fun averageColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 12, 12, true)
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            val lum = Color.red(c) + Color.green(c) + Color.blue(c)
            // Skip near-black and near-white pixels: they'd wash everything out to grey.
            if (lum in 60..690) {
                r += Color.red(c)
                g += Color.green(c)
                bl += Color.blue(c)
                n++
            }
        }
        if (small !== src) small.recycle()
        if (n == 0) return Color.rgb(0x4A, 0x3A, 0x5E)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun refreshLoved() {
        val t = state.track ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            loved = withContext(Dispatchers.IO) { Loved.isLoved(t.id) }
            val b = b ?: return@launch
            b.actions.npLove.setImageResource(if (loved) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            b.actions.npLove.imageTintList = ContextCompat.getColorStateList(requireContext(), if (loved) R.color.accent else R.color.text_2)
            b.actions.npLove.contentDescription = getString(if (loved) R.string.menu_unlove else R.string.menu_love)
        }
    }

    private fun renderDownload() {
        val b = b ?: return
        val t = state.track
        val d = t?.let { Downloads.state(it.id) }
        val btn = b.actions.npDownload
        val ring = b.actions.npDownloadProgress
        when {
            t == null || !t.isOnline -> {
                // Songs already on the phone: nothing to download.
                btn.setIcon(R.drawable.ic_download, R.color.text_3)
                btn.isEnabled = false
                btn.alpha = 0.4f
                ring.isVisible = false
            }
            d?.isDone == true -> {
                btn.alpha = 1f
                btn.setIcon(R.drawable.ic_downloaded, R.color.accent)
                btn.isEnabled = true
                btn.contentDescription = getString(R.string.state_downloaded)
                ring.isVisible = false
            }
            d?.isActive == true -> {
                btn.clearIcon()
                ring.isVisible = true
                ring.progress = (d.progress * 100).toInt().coerceAtLeast(3)
                btn.contentDescription = if (d.state == DownloadState.WAITING) getString(R.string.state_waiting) else getString(R.string.state_downloading, (d.progress * 100).toInt())
            }
            else -> {
                btn.alpha = 1f
                btn.setIcon(R.drawable.ic_download, R.color.text_2)
                btn.isEnabled = true
                btn.contentDescription = getString(R.string.np_download)
                ring.isVisible = false
            }
        }
    }

    private fun onDownload() {
        val t = state.track ?: return
        val d = Downloads.state(t.id)
        when {
            d?.isDone == true -> Messages.show(getString(R.string.toast_already_downloaded))
            d?.isActive == true -> Unit
            else -> SongActions.download(main, listOf(t))
        }
    }

    private fun tick() {
        val b = b ?: return
        val dur = state.durationMs
        val pos = PlayerHub.position()
        if (!dragging) {
            if (dur > 0) {
                b.seek.npSeek.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
                b.seek.npSeek.secondaryProgress = (PlayerHub.buffered() * 1000 / dur).toInt().coerceIn(0, 1000)
            } else {
                b.seek.npSeek.progress = 0
                b.seek.npSeek.secondaryProgress = 0
            }
            showTimes(pos)
        }
        (state.sleep as? Sleep.At)?.let {
            b.actions.npSleepBadge.setTextIfChanged(SleepTimer.badge(SleepTimer.secsLeft(it.endMs, SystemClock.elapsedRealtime())))
        }
        sleepPopup?.tick()
    }

    private fun showTimes(pos: Long) {
        val b = b ?: return
        val dur = state.durationMs
        b.seek.npElapsed.setTextIfChanged(Format.time(pos))
        b.seek.npRemaining.setTextIfChanged(if (dur > 0) "-" + Format.time((dur - pos).coerceAtLeast(0)) else "--:--")
    }

    private fun renderSleep() {
        val b = b ?: return
        val s = state.sleep
        val on = s != Sleep.Off
        b.actions.npSleep.setIcon(R.drawable.ic_moon, if (on) R.color.accent else R.color.text_2)
        when (s) {
            is Sleep.At -> {
                val left = SleepTimer.secsLeft(s.endMs, SystemClock.elapsedRealtime())
                b.actions.npSleepBadge.isVisible = true
                b.actions.npSleepBadge.setTextIfChanged(SleepTimer.badge(left))
                b.actions.npSleep.setPadding(b.root.dp(12), b.root.dp(9), b.root.dp(12), b.root.dp(15))
            }
            Sleep.EndOfSong -> {
                b.actions.npSleepBadge.isVisible = true
                b.actions.npSleepBadge.setTextIfChanged("•")
                b.actions.npSleep.setPadding(b.root.dp(12), b.root.dp(9), b.root.dp(12), b.root.dp(15))
            }
            Sleep.Off -> {
                b.actions.npSleepBadge.isVisible = false
                b.actions.npSleep.setPadding(b.root.dp(12), b.root.dp(12), b.root.dp(12), b.root.dp(12))
            }
        }
    }

    private fun openSleep() {
        val b = b ?: return
        sleepPopup?.dismiss()
        val p = SleepPopup(requireContext()) { sleepPopup = null }
        sleepPopup = p
        p.show(b.actions.npSleepBox, state.sleep)
    }

    // Lyrics ---------------------------------------------------------------------------------------

    private fun setLyricsOpen(open: Boolean, save: Boolean) {
        val b = b ?: return
        lyricsOpen = open
        b.npArt.visibility = if (open) View.INVISIBLE else View.VISIBLE
        lyricsPanel?.visible = open
        b.actions.npLyrics.setIcon(R.drawable.ic_lyrics, if (open) R.color.accent else R.color.text_2)
        b.actions.npLyrics.contentDescription = getString(if (open) R.string.np_lyrics_hide else R.string.np_lyrics)
        if (save) viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { Settings.lyricsOpen = open }
        if (open) {
            renderLyrics(LyricsCenter.shown.value)
            askLyrics()
        } else {
            stopLyricsTicks()
        }
    }

    /** Look up the song playing (only while the lyrics are open). */
    private fun askLyrics(force: Boolean = false, avoid: Long? = null) {
        if (!lyricsOpen) return
        val t = state.track ?: return
        val dur = state.durationMs
        val app = main.applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { LyricsCenter.request(app, t, dur, force, avoid) }
    }

    private fun renderLyrics(shown: LyricsCenter.Shown?) {
        val panel = lyricsPanel ?: return
        if (!lyricsOpen) return
        val t = state.track
        // Until the song playing has been looked up, what's there is another song's.
        val mine = shown?.takeIf { it.trackId == t?.id }
        panel.show(mine)
        lyricsRetry?.cancel()
        when (val st = mine?.state) {
            is LyricsState.Found -> {
                if (st.lyrics.synced) startLyricsTicks() else stopLyricsTicks()
                readAhead()
            }
            is LyricsState.Failed -> {
                stopLyricsTicks()
                // Asked again by itself after half a minute.
                lyricsRetry = viewLifecycleOwner.lifecycleScope.launch {
                    delay(30_000)
                    askLyrics()
                }
            }
            LyricsState.Missing -> {
                stopLyricsTicks()
                readAhead()
            }
            else -> stopLyricsTicks()
        }
    }

    /** The next song's lyrics, quietly, once the one playing is done. */
    private fun readAhead() {
        val next = PlayerHub.queue().second.firstOrNull()?.track ?: return
        val app = main.applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { LyricsCenter.ahead(app, next, 0) }
    }

    private fun startLyricsTicks() {
        if (lyricsJob?.isActive == true) return
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch {
            // The player's position already allows for the delay to the speakers (ExoPlayer
            // counts the audio output's latency), so it's used as it is.
            while (true) {
                val panel = lyricsPanel ?: break
                val wait = panel.tick(PlayerHub.position() / 1000f, state.isPlaying)
                delay(wait)
            }
        }
    }

    private fun stopLyricsTicks() {
        lyricsJob?.cancel()
        lyricsJob = null
    }

    /** For the CI screenshots: opens the sleep timer popup. */
    fun showSleepForTest() = openSleep()

    override fun onStop() {
        stopLyricsTicks()
        lyricsRetry?.cancel()
        super.onStop()
    }

    override fun onDestroyView() {
        sleepPopup?.dismiss()
        sleepPopup = null
        lyricsPanel = null
        // A new view starts from scratch: cover, sleep icon and the rest are drawn again.
        state = NowState()
        artId = null
        b = null
        super.onDestroyView()
    }
}
