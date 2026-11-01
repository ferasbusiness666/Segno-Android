package io.github.ferasbusiness666.segno.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.databinding.ViewEmptyBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun View.dp(v: Int): Int = context.dp(v)

/** Runs [block] while the fragment's view is started. */
fun Fragment.whileStarted(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED, block)
    }
}

/** Sets text only when it changed, so nothing is laid out again for nothing. */
fun TextView.setTextIfChanged(text: CharSequence) {
    if (this.text.toString() != text.toString()) this.text = text
}

/** Pads the view by the status bar at the top (and any side bars). */
fun View.padForStatusBar(extraTop: Int = 0) {
    val base = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(v.paddingLeft, base + bars.top + extraTop, v.paddingRight, v.paddingBottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Messages.show(getString(R.string.toast_no_browser))
    }
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Fills an empty-state view: icon, title, a line saying what to do next, and a button. */
fun ViewEmptyBinding.show(icon: Int, title: CharSequence, body: CharSequence, button: CharSequence? = null, onClick: (() -> Unit)? = null) {
    root.isVisible = true
    emptyIcon.setImageResource(icon)
    emptyTitle.text = title
    emptyBody.text = body
    emptyButton.isVisible = button != null
    if (button != null) {
        emptyButton.text = button
        emptyButton.setOnClickListener { onClick?.invoke() }
    }
}
