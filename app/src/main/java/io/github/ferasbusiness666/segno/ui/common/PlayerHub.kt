package io.github.ferasbusiness666.segno.ui.common

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.playback.Commands
import io.github.ferasbusiness666.segno.playback.MediaItems
import io.github.ferasbusiness666.segno.playback.PlaybackService
import io.github.ferasbusiness666.segno.playback.QueueOps
import io.github.ferasbusiness666.segno.playback.Sleep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the screens show about the player. */
data class NowState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val sleep: Sleep = Sleep.Off,
    /** Changes whenever the queue changes. */
    val queueVersion: Int = 0,
) {
    /** Pause shows while music plays or is about to; after an error, Play shows again. */
    val showPause: Boolean get() = isPlaying || (playWhenReady && buffering)
}

/** One song in the queue: where it is in the player's list. */
data class QueueEntry(val index: Int, val track: Track, val uid: Long)

/**
 * The screens' connection to the playback service. Connects lazily after the first frame and
 * turns player events into one [NowState].
 */
object PlayerHub {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var queueVersion = 0

    private val _state = MutableStateFlow(NowState())
    val state: StateFlow<NowState> = _state

    val connected: Boolean get() = controller != null

    fun connect(context: Context) {
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).setListener(sessionListener).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            refresh()
            val run = ArrayList(pending)
            pending.clear()
            run.forEach { it(c) }
        }, ContextCompat.getMainExecutor(app))
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        controller?.removeListener(playerListener)
        future = null
        controller = null
    }

    /** Runs [block] now if connected, or once connected. */
    private fun whenReady(context: Context?, block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pending += block
            context?.let { connect(it) }
        }
    }

    // Playing -------------------------------------------------------------------------------------

    /** Plays [tracks], starting at [index]. */
    fun play(context: Context, tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = tracks.map(MediaItems::from)
        whenReady(context) { c ->
            val start = if (shuffle) (tracks.indices).random() else index.coerceIn(0, tracks.size - 1)
            c.shuffleModeEnabled = false
            c.setMediaItems(items, start, C.TIME_UNSET)
            if (shuffle) c.shuffleModeEnabled = true
            c.prepare()
            c.play()
        }
    }

    fun playNext(context: Context, tracks: List<Track>) = send(context, Commands.QUEUE_NEXT, tracksBundle(tracks))

    fun addToQueue(context: Context, tracks: List<Track>) = send(context, Commands.QUEUE_ADD, tracksBundle(tracks))

    private fun tracksBundle(tracks: List<Track>) = Bundle().apply {
        putParcelableArrayList(Commands.ARG_TRACKS, ArrayList(tracks.map(MediaItems::toBundle)))
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = controller?.seekToNext()

    fun previous() = controller?.seekToPrevious()

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun position(): Long = controller?.currentPosition ?: 0

    fun buffered(): Long = controller?.bufferedPosition ?: 0

    fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // Queue -----------------------------------------------------------------------------------------

    /** The song playing and what comes after it, in the order they'll play. */
    fun queue(): Pair<QueueEntry?, List<QueueEntry>> {
        val c = controller ?: return null to emptyList()
        val tl = c.currentTimeline
        if (tl.isEmpty) return null to emptyList()
        val shuffled = c.shuffleModeEnabled
        val current = c.currentMediaItemIndex
        val seen = HashMap<String, Int>()
        fun entry(i: Int): QueueEntry {
            val item = c.getMediaItemAt(i)
            val n = (seen[item.mediaId] ?: 0) + 1
            seen[item.mediaId] = n
            return QueueEntry(i, MediaItems.toTrack(item), (item.mediaId.hashCode().toLong() shl 8) + n)
        }
        val next = ArrayList<QueueEntry>()
        var i = tl.getNextWindowIndex(current, Player.REPEAT_MODE_OFF, shuffled)
        while (i != C.INDEX_UNSET && next.size < tl.windowCount) {
            next += entry(i)
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffled)
        }
        return entry(current) to next
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun remove(index: Int) = controller?.removeMediaItem(index)

    /** Moves an upcoming song from [from] to [to] (positions in the upcoming list). */
    fun moveUpcoming(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to) return
        val current = c.currentMediaItemIndex
        if (c.shuffleModeEnabled) {
            val (_, next) = queue()
            val upcoming = next.map { it.index }
            if (from !in upcoming.indices || to !in upcoming.indices) return
            val order = playOrderBefore(c) + current + QueueOps.move(upcoming, from, to)
            if (order.size == c.mediaItemCount) {
                send(null, Commands.QUEUE_ORDER, Bundle().apply { putIntArray(Commands.ARG_ORDER, order.toIntArray()) })
            }
        } else {
            val count = c.mediaItemCount
            val a = current + 1 + from
            val b = current + 1 + to
            if (a < count && b < count) c.moveMediaItem(a, b)
        }
    }

    private fun playOrderBefore(c: MediaController): List<Int> {
        val tl = c.currentTimeline
        val out = ArrayList<Int>()
        var i = tl.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && i != c.currentMediaItemIndex && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    fun clearUpcoming() = send(null, Commands.QUEUE_CLEAR, Bundle.EMPTY)

    // Sleep timer -----------------------------------------------------------------------------------

    /** [minutes] 0 means the end of the song. */
    fun setSleep(minutes: Int) = send(null, Commands.SLEEP_SET, Bundle().apply { putInt(Commands.ARG_MINUTES, minutes) })

    fun extendSleep() = send(null, Commands.SLEEP_EXTEND, Bundle.EMPTY)

    fun sleepOff() = send(null, Commands.SLEEP_OFF, Bundle.EMPTY)

    private fun send(context: Context?, action: String, args: Bundle) {
        whenReady(context) { c -> c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
    }

    // State -----------------------------------------------------------------------------------------

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        _state.value = NowState(
            track = item?.let(MediaItems::toTrack),
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = duration,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = item != null,
            sleep = Commands.sleepFrom(c.sessionExtras),
            queueVersion = queueVersion,
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                queueVersion++
            }
            refresh()
        }
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            refresh()
        }

        override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (command.customAction == Commands.MESSAGE) {
                args.getString(Commands.ARG_TEXT)?.let { Messages.show(it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onDisconnected(controller: MediaController) {
            this@PlayerHub.controller = null
            future = null
        }
    }
}
