package io.github.ferasbusiness666.segno.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.spotify.SpotifyMatch
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.youtube.GoogleVideoReader
import io.github.ferasbusiness666.segno.youtube.StreamResolver
import io.github.ferasbusiness666.segno.youtube.YouTubeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Downloads songs one at a time while showing progress in a notification, so it keeps going
 * when you leave the app.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var done = 0
    private var failed = 0
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                Downloads.cancelWaiting()
                worker?.cancel()
            }
            return START_NOT_STICKY
        }
        channel(this)
        val n = progressNotification(getString(R.string.dl_getting_ready), null, 0, true)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        if (worker?.isActive != true) {
            done = 0
            failed = 0
            worker = scope.launch { work() }
        }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        Downloads.load()
        while (true) {
            coroutineContext.ensureActive()
            val track = Downloads.nextWaiting() ?: break
            try {
                download(track)
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                Downloads.setFailed(track.id, getString(R.string.dl_stopped))
                throw e
            } catch (e: Exception) {
                failed++
                Downloads.setFailed(track.id, reason(e))
            }
        }
        finish()
    }

    private fun reason(e: Exception): String = when (e) {
        is YouTubeException -> e.message.orEmpty()
        is IOException -> getString(R.string.dl_failed_network)
        else -> getString(R.string.dl_failed_other)
    }

    private suspend fun download(track: Track) {
        Downloads.setRunning(track.id, 0f)
        val waiting = Downloads.waitingCount()
        notifyProgress(track, 0, waiting, force = true)
        val videoId = track.youtubeId ?: SpotifyMatch.videoFor(track)
        val audio = StreamResolver.resolve(videoId, preferM4a = true)
        if (!audio.isM4a) throw YouTubeException(getString(R.string.dl_no_m4a))
        val dir = File(cacheDir, "downloads").apply { mkdirs() }
        val part = File(dir, videoId + ".part")
        try {
            val reader = GoogleVideoReader(audio, chunkSize = 4L shl 20) {
                StreamResolver.invalidate(videoId)
                StreamResolver.resolve(videoId)
            }
            try {
                val total = reader.open(0, -1)
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = reader.read(buf, 0, buf.size)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            Downloads.setRunning(track.id, got.toFloat() / total)
                            notifyProgress(track, pct, waiting, force = false)
                        }
                    }
                }
            } finally {
                reader.close()
            }
            notifyProgress(track, 100, waiting, force = true)
            val cover = SongSaver.cover(track)
            val uri = SongSaver.save(this, track, part, cover)
            Downloads.setDone(track.id, uri)
        } finally {
            part.delete()
        }
    }

    private fun notifyProgress(track: Track, pct: Int, waiting: Int, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        val more = if (waiting > 1) resources.getQuantityString(R.plurals.dl_more_waiting, waiting - 1, waiting - 1) else null
        val n = progressNotification(track.title, more, pct, pct == 0)
        NotificationManagerCompat.from(this).let {
            if (it.areNotificationsEnabled()) runCatching { it.notify(NOTIF_ID, n) }
        }
    }

    private fun progressNotification(title: String, text: String?, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_segno)
            .setContentTitle(getString(R.string.dl_notif_title, title))
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.dl_stop), stopIntent())
            .build()

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (done + failed > 0) {
            val title = if (failed == 0) {
                resources.getQuantityString(R.plurals.dl_done_title, done, done)
            } else {
                resources.getQuantityString(R.plurals.dl_done_some_failed, failed, done, failed)
            }
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_segno)
                .setContentTitle(title)
                .setContentText(getString(R.string.dl_done_text))
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(openApp())
                .build()
            NotificationManagerCompat.from(this).let {
                if (it.areNotificationsEnabled()) runCatching { it.notify(DONE_ID, n) }
            }
        }
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits how long data-sync work may run in the background.
        worker?.cancel()
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val DONE_ID = 43
        const val ACTION_STOP = "io.github.ferasbusiness666.segno.STOP_DOWNLOADS"

        fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, context.getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }
}
