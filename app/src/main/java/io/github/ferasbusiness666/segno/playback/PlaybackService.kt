@file:OptIn(UnstableApi::class)

package io.github.ferasbusiness666.segno.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.os.BundleCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.History
import io.github.ferasbusiness666.segno.data.Related
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.library.LocalMusic
import io.github.ferasbusiness666.segno.spotify.SpotifyMatch
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.youtube.RefusedException
import io.github.ferasbusiness666.segno.youtube.StreamResolver
import io.github.ferasbusiness666.segno.youtube.Thumbs
import io.github.ferasbusiness666.segno.youtube.YouTubeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays music in the background: ExoPlayer inside a MediaSession, which gives the notification,
 * the lock screen controls and Bluetooth and headset buttons. Also runs the sleep timer, keeps
 * the music going with related songs when the queue ends, and remembers the queue.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var sleep: Sleep = Sleep.Off
    private var failuresInARow = 0
    private var recordedId: String? = null
    private var autoplayFor: String? = null

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val sources = DefaultMediaSourceFactory(SegnoDataSource.Factory(this))
            .setLoadErrorHandlingPolicy(ErrorPolicy())
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Less buffered audio than the default: fine for music and lighter on weak phones.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 50_000, 1_000, 2_000).build())
            .build()
        player.addListener(listener)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(open)
            .setSessionExtras(Commands.sleepExtras(Sleep.Off))
            .build()

        val notifications = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.notif_channel_playback)
            .build()
        notifications.setSmallIcon(R.drawable.ic_stat_segno)
        setMediaNotificationProvider(notifications)

        SegnoDataSource.onSpotifyMatched = { id, video -> handler.post { onSpotifyMatched(id, video) } }
        restore()
        if (io.github.ferasbusiness666.segno.BuildConfig.DEBUG) handler.postDelayed(ciLog, 5000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveNow()
        SegnoDataSource.onSpotifyMatched = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // The queue ----------------------------------------------------------------------------------

    private fun restore() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                Downloads.load()
                QueueStore.load(this@PlaybackService)
            } ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            player.setMediaItems(saved.tracks.map(MediaItems::from), saved.index, saved.positionMs)
            player.repeatMode = saved.repeat
            if (saved.shuffle) player.shuffleModeEnabled = true
        }
    }

    private val saveTask = Runnable { saveNow() }

    private fun scheduleSave() {
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, 1500)
    }

    private fun saveNow() {
        if (!::player.isInitialized) return
        handler.removeCallbacks(saveTask)
        val count = player.mediaItemCount
        val tracks = (0 until count).map { MediaItems.toTrack(player.getMediaItemAt(it)) }
        val saved = QueueStore.Saved(
            tracks = tracks,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
        )
        val app = applicationContext
        Thread { runCatching { QueueStore.save(app, saved) } }.start()
    }

    /** The order songs will play in: playlist positions, following shuffle when it's on. */
    private fun playOrder(): List<Int> {
        val tl = player.currentTimeline
        if (tl.isEmpty) return emptyList()
        val shuffled = player.shuffleModeEnabled
        val out = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(shuffled)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffled)
        }
        return out
    }

    private fun setOrder(order: IntArray) {
        if (order.size != player.mediaItemCount) return
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, SystemClock.elapsedRealtime()))
    }

    private fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map(MediaItems::from)
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            player.play()
            return
        }
        val current = player.currentMediaItemIndex
        val at = current + 1
        player.addMediaItems(at, items)
        if (player.shuffleModeEnabled) setOrder(QueueOps.withNext(playOrder(), current, (at until at + items.size).toList()))
    }

    private fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map(MediaItems::from)
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            player.play()
            return
        }
        val at = player.mediaItemCount
        player.addMediaItems(items)
        if (player.shuffleModeEnabled) setOrder(QueueOps.withLast(playOrder(), (at until at + items.size).toList()))
    }

    /** Removes everything after the song playing. */
    private fun clearUpcoming() {
        val order = playOrder()
        val current = player.currentMediaItemIndex
        val pos = order.indexOf(current)
        if (pos < 0) return
        val drop = order.drop(pos + 1).sortedDescending()
        for (i in drop) player.removeMediaItem(i)
    }

    // Keeping the music going ---------------------------------------------------------------------

    private fun maybeAutoplay() {
        if (!Settings.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount == 0 || player.hasNextMediaItem()) return
        val item = player.currentMediaItem ?: return
        val track = MediaItems.toTrack(item)
        if (autoplayFor == track.id) return
        autoplayFor = track.id
        val inQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val app = applicationContext
        scope.launch {
            val more = withContext(Dispatchers.IO) {
                runCatching {
                    if (track.isOnline) {
                        val video = track.youtubeId ?: SpotifyMatch.known(track.id) ?: SpotifyMatch.videoFor(track)
                        QueueOps.pickRelated(StreamResolver.resolve(video).related, inQueue)
                    } else {
                        LocalMusic.songs(app).filter { it.id !in inQueue }.shuffled().take(10)
                    }
                }.getOrDefault(emptyList())
            }
            if (more.isNotEmpty() && player.currentMediaItem?.mediaId == track.id && !player.hasNextMediaItem()) {
                player.addMediaItems(more.map(MediaItems::from))
            }
        }
    }

    /** Remembers a song once it really plays, for Home's "Recently played" and suggestions. */
    private fun maybeRecord() {
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        if (item.mediaId == recordedId) return
        recordedId = item.mediaId
        val track = MediaItems.toTrack(item)
        scope.launch(Dispatchers.IO) {
            runCatching {
                History.add(track)
                val video = track.youtubeId ?: SpotifyMatch.known(track.id)
                val related = video?.let { StreamResolver.cached(it)?.related }
                if (!related.isNullOrEmpty()) Related.save(track, related.take(12))
            }
        }
    }

    /** Debug builds log the position every few seconds, so CI can see music keep going in the background. */
    private val ciLog = object : Runnable {
        override fun run() {
            android.util.Log.i("SegnoCI", "pos=${player.currentPosition} playing=${player.isPlaying} state=${player.playbackState}")
            handler.postDelayed(this, 5000)
        }
    }

    private val prefetchTask = Runnable { prefetchNext() }

    /** Looks up the next song's audio address a little before it's needed. */
    private fun prefetchNext() {
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        val track = MediaItems.toTrack(player.getMediaItemAt(next))
        if (!track.isOnline || Downloads.state(track.id)?.isDone == true) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val video = track.youtubeId ?: SpotifyMatch.videoFor(track)
                StreamResolver.prefetch(video)
            }
        }
    }

    private fun onSpotifyMatched(trackId: String, videoId: String) {
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (item.mediaId != trackId) continue
            val track = MediaItems.toTrack(item)
            if (track.video == videoId && track.art.isNotEmpty()) continue
            val updated = track.copy(video = videoId, art = track.art.ifEmpty { Thumbs.video(videoId, big = true) })
            player.replaceMediaItem(i, item.buildUpon().setMediaMetadata(MediaItems.metadata(updated)).build())
        }
    }

    // Errors ---------------------------------------------------------------------------------------

    private fun handleError(error: PlaybackException) {
        val causes = generateSequence(error as Throwable) { it.cause }.toList()
        val yt = causes.filterIsInstance<YouTubeException>().firstOrNull()
        val refused = causes.any { it is RefusedException }
        val badFile = error.errorCode in 3000..4999 || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        val songProblem = (yt != null && !yt.network) || refused || badFile
        if (songProblem) {
            failuresInARow++
            val why = yt?.message ?: getString(R.string.err_play_generic)
            if (player.hasNextMediaItem() && failuresInARow < 4) {
                message(getString(R.string.err_skipping, title, why))
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                message(getString(R.string.err_cant_play, title, why))
            }
        } else {
            message(yt?.message ?: getString(R.string.err_network_play))
        }
    }

    private fun message(text: String) {
        session.broadcastCustomCommand(Commands.command(Commands.MESSAGE), Bundle().apply { putString(Commands.ARG_TEXT, text) })
    }

    // Sleep timer ----------------------------------------------------------------------------------

    private val sleepTick = object : Runnable {
        override fun run() {
            tickSleep()
            if (sleep != Sleep.Off) handler.postDelayed(this, 250)
        }
    }

    private fun setSleep(s: Sleep) {
        sleep = s
        player.pauseAtEndOfMediaItems = s == Sleep.EndOfSong
        handler.removeCallbacks(sleepTick)
        if (s == Sleep.Off) {
            player.volume = 1f
        } else {
            handler.post(sleepTick)
        }
        session.setSessionExtras(Commands.sleepExtras(s))
    }

    private fun tickSleep() {
        val s = sleep
        if (s == Sleep.Off) return
        val duration = player.duration
        val songLeft = if (duration != C.TIME_UNSET && duration > 0) duration - player.currentPosition else null
        val left = SleepTimer.remainingMs(s, SystemClock.elapsedRealtime(), songLeft)
        if (s is Sleep.At && left != null && left <= 0) {
            finishSleep()
            return
        }
        player.volume = SleepTimer.volumeFor(left)
    }

    /** The timer is up: pause, and put the volume back for next time. */
    private fun finishSleep() {
        player.pause()
        setSleep(Sleep.Off)
    }

    // Player events --------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recordedId = null
            maybeRecord()
            maybeAutoplay()
            scheduleSave()
            handler.removeCallbacks(prefetchTask)
            handler.postDelayed(prefetchTask, 15_000)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                failuresInARow = 0
                maybeRecord()
            } else {
                scheduleSave()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleep == Sleep.EndOfSong) {
                setSleep(Sleep.Off)
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                scheduleSave()
                maybeAutoplay()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // The song playing stays first; the rest come in a fresh random order.
            if (shuffleModeEnabled && player.mediaItemCount > 1) {
                setOrder(QueueOps.anchoredShuffle(player.mediaItemCount, player.currentMediaItemIndex))
            }
            scheduleSave()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            scheduleSave()
            if (repeatMode == Player.REPEAT_MODE_OFF) maybeAutoplay()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    // Session callback -----------------------------------------------------------------------------

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            // Segno's own screens also get the sleep timer and queue commands.
            if (controller.packageName == packageName) {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                    Commands.incoming.forEach { add(Commands.command(it)) }
                }.build()
                builder.setAvailableSessionCommands(commands)
            }
            return builder.build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItems::playable).toMutableList())

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                Commands.SLEEP_SET -> {
                    val minutes = args.getInt(Commands.ARG_MINUTES)
                    setSleep(if (minutes <= 0) Sleep.EndOfSong else SleepTimer.after(SystemClock.elapsedRealtime(), minutes))
                }
                Commands.SLEEP_EXTEND -> (sleep as? Sleep.At)?.let { setSleep(SleepTimer.extend(it, SystemClock.elapsedRealtime())) }
                Commands.SLEEP_OFF -> setSleep(Sleep.Off)
                Commands.QUEUE_NEXT -> playNext(tracksFrom(args))
                Commands.QUEUE_ADD -> addToQueue(tracksFrom(args))
                Commands.QUEUE_ORDER -> args.getIntArray(Commands.ARG_ORDER)?.let { if (player.shuffleModeEnabled) setOrder(it) }
                Commands.QUEUE_CLEAR -> clearUpcoming()
                else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val saved = withContext(Dispatchers.IO) { QueueStore.load(this@PlaybackService) }
                if (saved == null) {
                    future.setException(UnsupportedOperationException("Nothing to resume"))
                } else {
                    future.set(MediaSession.MediaItemsWithStartPosition(saved.tracks.map(MediaItems::from), saved.index, saved.positionMs))
                }
            }
            return future
        }
    }

    private fun tracksFrom(args: Bundle): List<Track> =
        BundleCompat.getParcelableArrayList(args, Commands.ARG_TRACKS, Bundle::class.java).orEmpty().map(MediaItems::fromBundle)

    /** Doesn't retry songs YouTube won't play; retries network trouble as usual. */
    private class ErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val unplayable = generateSequence(loadErrorInfo.exception as Throwable) { it.cause }
                .any { (it is YouTubeException && !it.network) || it is RefusedException }
            return if (unplayable) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
