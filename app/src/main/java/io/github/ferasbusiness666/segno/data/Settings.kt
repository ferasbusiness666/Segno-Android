package io.github.ferasbusiness666.segno.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.ferasbusiness666.segno.SegnoApp

/** Small settings, kept in SharedPreferences. Loaded off the main thread at startup. */
object Settings {
    private val prefs: SharedPreferences by lazy {
        SegnoApp.instance.getSharedPreferences("segno", Context.MODE_PRIVATE)
    }

    /** Touch the file once in the background so later reads don't wait for the disk. */
    fun warmUp() {
        prefs.all
    }

    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) = prefs.edit { putBoolean("autoplay", v) }

    var checkUpdates: Boolean
        get() = prefs.getBoolean("check_updates", true)
        set(v) = prefs.edit { putBoolean("check_updates", v) }

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(v) = prefs.edit { putLong("last_update_check", v) }

    /** A newer version found by the update check ("1.0.1"), its download link, and its page. */
    var updateVersion: String
        get() = prefs.getString("update_version", "").orEmpty()
        set(v) = prefs.edit { putString("update_version", v) }

    var updateUrl: String
        get() = prefs.getString("update_url", "").orEmpty()
        set(v) = prefs.edit { putString("update_url", v) }

    /** The version whose banner was closed, so it doesn't come back. */
    var updateDismissed: String
        get() = prefs.getString("update_dismissed", "").orEmpty()
        set(v) = prefs.edit { putString("update_dismissed", v) }

    /** "music_songs" or "videos". */
    var searchFilter: String
        get() = prefs.getString("search_filter", "music_songs").orEmpty()
        set(v) = prefs.edit { putString("search_filter", v) }

    var recentSearches: List<String>
        get() = prefs.getString("recent_searches", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit { putString("recent_searches", v.take(8).joinToString("\n")) }

    var libraryTab: Int
        get() = prefs.getInt("library_tab", 0)
        set(v) = prefs.edit { putInt("library_tab", v) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit { putBoolean("asked_notifications", v) }
}
