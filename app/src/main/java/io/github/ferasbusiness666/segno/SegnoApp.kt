package io.github.ferasbusiness666.segno

import android.app.Application
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.ferasbusiness666.segno.core.Net
import io.github.ferasbusiness666.segno.data.Db
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.download.Downloads
import java.io.File
import java.util.concurrent.Executors

/**
 * Nothing slow happens here: the database, settings and downloads list are read on a background
 * thread, and YouTube (NewPipe) is set up the first time it's used.
 */
class SegnoApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) {
            val policy = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog()
            if (Build.VERSION.SDK_INT >= 28) {
                // Also a short list in files/strictmode.txt, which CI reads back.
                val io = Executors.newSingleThreadExecutor()
                policy.penaltyListener(io) { v ->
                    // Segno's own frame if there is one, else the first one outside Android itself.
                    val system = listOf("android.", "com.android.", "java.", "javax.", "libcore.", "dalvik.", "sun.", "kotlin.")
                    val where = v.stackTrace.firstOrNull { it.className.startsWith("io.github.ferasbusiness666") }
                        ?: v.stackTrace.firstOrNull { f -> system.none { f.className.startsWith(it) } }
                        ?: v.stackTrace.drop(2).take(14).joinToString(" < ")
                    runCatching { File(filesDir, "strictmode.txt").appendText("${v.javaClass.simpleName} at $where\n") }
                }
            }
            StrictMode.setThreadPolicy(policy.build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        Thread({
            runCatching {
                Settings.warmUp()
                Db.get(this).readableDatabase
                Downloads.load()
            }
        }, "segno-warmup").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** Pictures: shared HTTP client, a modest memory cache for weak phones, no fades. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Net.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .crossfade(false)
            .build()

    companion object {
        lateinit var instance: SegnoApp
            private set
    }
}
