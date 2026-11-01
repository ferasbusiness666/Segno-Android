package io.github.ferasbusiness666.segno.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Segno's own commands between the screens and the playback service. */
object Commands {
    const val SLEEP_SET = "segno.sleep.set"
    const val SLEEP_EXTEND = "segno.sleep.extend"
    const val SLEEP_OFF = "segno.sleep.off"
    const val QUEUE_NEXT = "segno.queue.next"
    const val QUEUE_ADD = "segno.queue.add"
    const val QUEUE_ORDER = "segno.queue.order"
    const val QUEUE_CLEAR = "segno.queue.clear"

    /** From the service: a short message to show. */
    const val MESSAGE = "segno.message"

    const val ARG_MINUTES = "minutes"
    const val ARG_TRACKS = "tracks"
    const val ARG_ORDER = "order"
    const val ARG_TEXT = "text"

    /** Session extras: the sleep timer's state. */
    const val EXTRA_SLEEP_MODE = "segno.sleep.mode"
    const val EXTRA_SLEEP_END = "segno.sleep.end"

    const val MODE_OFF = 0
    const val MODE_AT = 1
    const val MODE_END_OF_SONG = 2

    val incoming = listOf(SLEEP_SET, SLEEP_EXTEND, SLEEP_OFF, QUEUE_NEXT, QUEUE_ADD, QUEUE_ORDER, QUEUE_CLEAR)

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    fun sleepFrom(extras: Bundle?): Sleep = when (extras?.getInt(EXTRA_SLEEP_MODE, MODE_OFF) ?: MODE_OFF) {
        MODE_AT -> Sleep.At(extras!!.getLong(EXTRA_SLEEP_END))
        MODE_END_OF_SONG -> Sleep.EndOfSong
        else -> Sleep.Off
    }

    fun sleepExtras(sleep: Sleep) = Bundle().apply {
        when (sleep) {
            Sleep.Off -> putInt(EXTRA_SLEEP_MODE, MODE_OFF)
            Sleep.EndOfSong -> putInt(EXTRA_SLEEP_MODE, MODE_END_OF_SONG)
            is Sleep.At -> {
                putInt(EXTRA_SLEEP_MODE, MODE_AT)
                putLong(EXTRA_SLEEP_END, sleep.endMs)
            }
        }
    }
}
