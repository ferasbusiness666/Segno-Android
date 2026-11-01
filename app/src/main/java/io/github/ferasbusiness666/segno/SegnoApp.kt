package io.github.ferasbusiness666.segno

import android.app.Application
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

/**
 * Nothing slow happens here: the database, settings and downloads list are read on a background
 * thread, and YouTube (NewPipe) is set up the first time it's used.
 */
class SegnoApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
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
