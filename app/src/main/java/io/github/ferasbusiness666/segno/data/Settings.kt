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

    /** Look up lyrics online when they're opened (desktop's "Synced lyrics"). Off sends nothing. */
    var syncedLyrics: Boolean
        get() = prefs.getBoolean("synced_lyrics", true)
        set(v) = prefs.edit { putBoolean("synced_lyrics", v) }

    /** Now Playing shows the lyrics instead of the cover. */
    var lyricsOpen: Boolean
        get() = prefs.getBoolean("lyrics_open", false)
        set(v) = prefs.edit { putBoolean("lyrics_open", v) }

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

    /** The APK file in that release (empty: none, so the page opens instead), and a few lines of its notes. */
    var updateApk: String
        get() = prefs.getString("update_apk", "").orEmpty()
        set(v) = prefs.edit { putString("update_apk", v) }

    var updateNotes: String
        get() = prefs.getString("update_notes", "").orEmpty()
        set(v) = prefs.edit { putString("update_notes", v) }

    /** Whether the "Segno needs your OK to install updates" note was shown. */
    var explainedInstalls: Boolean
        get() = prefs.getBoolean("explained_installs", false)
        set(v) = prefs.edit { putBoolean("explained_installs", v) }

    /** The version whose banner was closed, so it doesn't come back. */
    var updateDismissed: String
        get() = prefs.getString("update_dismissed", "").orEmpty()
        set(v) = prefs.edit { putString("update_dismissed", v) }

    /** Where Search looks: YouTube.SONGS ("music_songs", YouTube Music) or YouTube.VIDEOS ("videos"). */
    var searchSource: String
        get() = prefs.getString("search_source", "music_songs").orEmpty()
        set(v) = prefs.edit { putString("search_source", v) }

    var recentSearches: List<String>
        get() = prefs.getString("recent_searches", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit { putString("recent_searches", v.take(8).joinToString("\n")) }

    /** The Library page picked last: "songs" (the default), "playlists", "albums" or "artists". */
    var libraryPage: String
        get() = prefs.getString("library_page", "songs").orEmpty()
        set(v) = prefs.edit { putString("library_page", v) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit { putBoolean("asked_notifications", v) }
}
