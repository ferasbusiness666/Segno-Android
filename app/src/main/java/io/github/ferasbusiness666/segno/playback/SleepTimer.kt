package io.github.ferasbusiness666.segno.playback

import kotlin.math.ceil

/** The sleep timer's state. Times are SystemClock.elapsedRealtime() milliseconds. */
sealed interface Sleep {
    data object Off : Sleep
    data class At(val endMs: Long) : Sleep
    data object EndOfSong : Sleep
}

/**
 * The sleep timer's arithmetic, kept apart from the player so it can be tested. Like desktop:
 * 15, 30 or 45 minutes, an hour, or the end of the song. The last 30 seconds fade out, then the
 * music pauses and the volume goes back up.
 */
object SleepTimer {
    const val FADE_MS = 30_000L
    const val EXTEND_MS = 10 * 60_000L
    val MINUTES = listOf(15, 30, 45, 60)

    fun after(nowMs: Long, minutes: Int): Sleep.At = Sleep.At(nowMs + minutes * 60_000L)

    /** "+10 min": from the planned end, or from now if that has passed. */
    fun extend(at: Sleep.At, nowMs: Long): Sleep.At = Sleep.At(maxOf(at.endMs, nowMs) + EXTEND_MS)

    /**
     * Milliseconds until the music stops, or null when it doesn't stop (timer off, or the song's
     * length isn't known yet). [songLeftMs] is what's left of the current song.
     */
    fun remainingMs(sleep: Sleep, nowMs: Long, songLeftMs: Long?): Long? = when (sleep) {
        Sleep.Off -> null
        is Sleep.At -> sleep.endMs - nowMs
        Sleep.EndOfSong -> songLeftMs
    }

    /** The volume for the fade: full until the last 30 seconds, then down to nothing. */
    fun volumeFor(remainingMs: Long?): Float {
        if (remainingMs == null || remainingMs >= FADE_MS) return 1f
        if (remainingMs <= 0) return 0f
        return (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    /** Whole seconds left, rounded up so it never shows 0:00 while music plays. */
    fun secsLeft(endMs: Long, nowMs: Long): Long = ceil((endMs - nowMs).coerceAtLeast(0) / 1000.0).toLong()

    /** "23:41", or "1:05:00" past an hour. */
    fun clock(secs: Long): String {
        val h = secs / 3600
        val m = secs / 60 % 60
        val s = secs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Under the moon: "23m", or "45s" in the last minute. */
    fun badge(secs: Long): String = if (secs >= 60) "${(secs + 59) / 60}m" else "${secs}s"
}
