package io.github.ferasbusiness666.segno.ui.nowplaying

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.view.isVisible
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.databinding.PopupSleepBinding
import io.github.ferasbusiness666.segno.playback.Sleep
import io.github.ferasbusiness666.segno.playback.SleepTimer
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.setTextIfChanged

/**
 * The sleep timer, like desktop: pick 15, 30 or 45 minutes, an hour, or the end of the song.
 * While it's on, it counts down and offers "+10 min" and "Turn off".
 */
class SleepPopup(private val context: Context, private val onGone: () -> Unit) {
    private val b = PopupSleepBinding.inflate(LayoutInflater.from(context))
    private val width = context.dp(288)
    private val window = PopupWindow(b.root, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    private var sleep: Sleep = Sleep.Off

    init {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.elevation = context.dp(12).toFloat()
        window.animationStyle = android.R.style.Animation_Dialog
        window.setOnDismissListener { onGone() }
        val choices = listOf(b.sleep15 to 15, b.sleep30 to 30, b.sleep45 to 45, b.sleep60 to 60)
        for ((v, minutes) in choices) {
            v.setOnClickListener {
                PlayerHub.setSleep(minutes)
                Messages.show(context.getString(R.string.toast_sleep_set, (v as android.widget.TextView).text))
                window.dismiss()
            }
        }
        b.sleepEnd.setOnClickListener {
            PlayerHub.setSleep(0)
            Messages.show(context.getString(R.string.toast_sleep_end))
            window.dismiss()
        }
        b.sleepPlus.setOnClickListener { PlayerHub.extendSleep() }
        b.sleepOff.setOnClickListener {
            PlayerHub.sleepOff()
            Messages.show(context.getString(R.string.toast_sleep_off))
            window.dismiss()
        }
    }

    /** Shows the popup above [anchor], its right edge lined up with the anchor's. */
    fun show(anchor: View, s: Sleep) {
        render(s)
        b.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val h = b.root.measuredHeight
        val w = width
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = anchor.rootView.width
        val margin = context.dp(12)
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceIn(margin, (screenW - w - margin).coerceAtLeast(margin))
        val y = (loc[1] - h - context.dp(8)).coerceAtLeast(margin)
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    fun render(s: Sleep) {
        sleep = s
        val off = s == Sleep.Off
        b.sleepRow1.isVisible = off
        b.sleepRow2.isVisible = off
        b.sleepActive.isVisible = !off
        b.sleepPlus.isVisible = s is Sleep.At
        when (s) {
            Sleep.Off -> {
                b.sleepTitle.isVisible = true
                b.sleepTitle.setText(R.string.sleep_title)
                b.sleepSub.setText(R.string.sleep_sub)
                b.sleepClock.isVisible = false
            }
            is Sleep.At -> {
                b.sleepTitle.isVisible = false
                b.sleepSub.setText(R.string.sleep_stops_in)
                b.sleepClock.isVisible = true
                tick()
            }
            Sleep.EndOfSong -> {
                b.sleepTitle.isVisible = true
                b.sleepTitle.setText(R.string.sleep_title)
                b.sleepSub.setText(R.string.sleep_after_song)
                b.sleepClock.isVisible = false
            }
        }
    }

    /** Updates the countdown. */
    fun tick() {
        val s = sleep as? Sleep.At ?: return
        b.sleepClock.setTextIfChanged(SleepTimer.clock(SleepTimer.secsLeft(s.endMs, SystemClock.elapsedRealtime())))
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
    }
}
