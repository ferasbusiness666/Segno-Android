package io.github.ferasbusiness666.segno.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app, so connections are shared. Created on first use. */
object Net {
    const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
