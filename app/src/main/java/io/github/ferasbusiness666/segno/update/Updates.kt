package io.github.ferasbusiness666.segno.update

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import io.github.ferasbusiness666.segno.BuildConfig
import io.github.ferasbusiness666.segno.core.Net
import io.github.ferasbusiness666.segno.core.Versions
import io.github.ferasbusiness666.segno.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request

/**
 * Asks GitHub, at most once a day, whether a newer Segno is out. Nothing else is sent: no ids,
 * no device details, just the request for the latest release.
 */
object Updates {
    const val REPO = "ferasbusiness666/segno-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Release(val version: String, val url: String)

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val release: Release) : Result
        data object Failed : Result
    }

    private val _available = MutableStateFlow<Release?>(null)

    /** A newer version, if one was found (kept from the last check). */
    val available: StateFlow<Release?> = _available

    /** Reads the last check's result. Blocking. */
    fun load() {
        val v = Settings.updateVersion
        _available.value = if (v.isNotEmpty() && Versions.isNewer(v, BuildConfig.VERSION_NAME)) Release(v, Settings.updateUrl) else null
    }

    /** Checks if the last check was a day ago or more. Blocking. */
    fun checkIfDue() {
        load()
        if (!Settings.checkUpdates) return
        if (System.currentTimeMillis() - Settings.lastUpdateCheck < DAY_MS) return
        check()
    }

    /** Asks GitHub now. Blocking. */
    fun check(): Result {
        val req = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Segno-Android/${BuildConfig.VERSION_NAME}")
            .build()
        val body = try {
            Net.client.newCall(req).execute().use { r ->
                if (r.code == 404) {
                    // No release published yet.
                    Settings.lastUpdateCheck = System.currentTimeMillis()
                    return Result.UpToDate
                }
                if (!r.isSuccessful) return Result.Failed
                r.body.string()
            }
        } catch (e: Exception) {
            return Result.Failed
        }
        Settings.lastUpdateCheck = System.currentTimeMillis()
        val release = parse(body) ?: return Result.Failed
        return if (Versions.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            Settings.updateVersion = release.version
            Settings.updateUrl = release.url
            _available.value = release
            Result.Available(release)
        } else {
            Settings.updateVersion = ""
            _available.value = null
            Result.UpToDate
        }
    }

    /** The version and APK link in GitHub's answer. Falls back to the release page. */
    fun parse(json: String): Release? = try {
        val o: JsonObject = JsonParser.`object`().from(json)
        if (o.getBoolean("draft", false) || o.getBoolean("prerelease", false)) {
            null
        } else {
            val tag = o.getString("tag_name").orEmpty().removePrefix("v").removePrefix("V")
            val apk = o.getArray("assets").filterIsInstance<JsonObject>()
                .firstOrNull { it.getString("name").orEmpty().endsWith(".apk", ignoreCase = true) }
                ?.getString("browser_download_url")
            if (tag.isEmpty()) null else Release(tag, apk ?: o.getString("html_url") ?: RELEASES_PAGE)
        }
    } catch (e: Exception) {
        null
    }

    fun dismiss() {
        _available.value?.let { Settings.updateDismissed = it.version }
        _available.value = null
    }

    /** Whether to show the banner: there's a newer version and its banner wasn't closed. */
    fun bannerFor(r: Release?): Release? = r?.takeIf { it.version != Settings.updateDismissed }
}
