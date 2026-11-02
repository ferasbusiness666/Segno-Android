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
    const val REPO = "ferasbusiness666/Segno-Android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * A release. [apkUrl]: its Segno-<version>.apk, or empty when it has none (then [pageUrl] opens
     * in the browser). [notes]: the first few lines of its notes, ready to show.
     */
    data class Release(
        val version: String,
        val pageUrl: String,
        val apkUrl: String = "",
        val apkName: String = "",
        val notes: String = "",
    )

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
        _available.value = if (v.isNotEmpty() && Versions.isNewer(v, BuildConfig.VERSION_NAME)) {
            val apk = Settings.updateApk
            Release(v, Settings.updateUrl.ifEmpty { RELEASES_PAGE }, apk, apk.substringAfterLast('/'), Settings.updateNotes)
        } else {
            null
        }
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
            remember(release)
            Result.Available(release)
        } else {
            Settings.updateVersion = ""
            _available.value = null
            Result.UpToDate
        }
    }

    private fun remember(release: Release) {
        Settings.updateVersion = release.version
        Settings.updateUrl = release.pageUrl
        Settings.updateApk = release.apkUrl
        Settings.updateNotes = release.notes
        _available.value = release
    }

    /** The version, APK and notes in GitHub's answer. */
    fun parse(json: String): Release? = try {
        val o: JsonObject = JsonParser.`object`().from(json)
        if (o.getBoolean("draft", false) || o.getBoolean("prerelease", false)) {
            null
        } else {
            val tag = o.getString("tag_name").orEmpty().removePrefix("v").removePrefix("V")
            val assets = o.getArray("assets").orEmpty().filterIsInstance<JsonObject>()
                .map { Asset(it.getString("name").orEmpty(), it.getString("browser_download_url").orEmpty()) }
            val apk = pickApk(assets, tag)
            if (tag.isEmpty()) {
                null
            } else {
                Release(
                    version = tag,
                    pageUrl = o.getString("html_url") ?: RELEASES_PAGE,
                    apkUrl = apk?.url.orEmpty(),
                    apkName = apk?.name.orEmpty(),
                    notes = shortNotes(o.getString("body").orEmpty()),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    class Asset(val name: String, val url: String)

    /**
     * The APK to install: "Segno-<version>.apk" (the name the release workflow gives it), else
     * another Segno-*.apk that isn't a debug build. Only https links on GitHub count.
     */
    fun pickApk(assets: List<Asset>, version: String): Asset? {
        val usable = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.url.startsWith("https://github.com/") &&
                !it.name.contains("debug", ignoreCase = true)
        }
        return usable.firstOrNull { it.name.equals("Segno-$version.apk", ignoreCase = true) }
            ?: usable.firstOrNull { it.name.startsWith("Segno-", ignoreCase = true) }
    }

    /** A few lines of release notes: the change lines without Markdown, at most [max] of them. */
    fun shortNotes(body: String, max: Int = 4): String {
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val shown = lines.take(max).joinToString("\n") { "• $it" }
        return if (lines.size > max) "$shown\n…" else shown
    }

    fun dismiss() {
        _available.value?.let { Settings.updateDismissed = it.version }
        _available.value = null
    }

    /** Whether to show the card: there's a newer version and its card wasn't closed. */
    fun bannerFor(r: Release?): Release? = r?.takeIf { it.version != Settings.updateDismissed }
}
