package io.github.ferasbusiness666.segno.ui.nowplaying

import android.content.Context
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.databinding.ViewNpLyricsBinding
import io.github.ferasbusiness666.segno.lyrics.Line
import io.github.ferasbusiness666.segno.lyrics.Lyrics
import io.github.ferasbusiness666.segno.lyrics.LyricsCenter
import io.github.ferasbusiness666.segno.lyrics.LyricsState
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.quietChanges
import io.github.ferasbusiness666.segno.ui.common.setTextIfChanged
import java.util.Locale
import kotlin.math.abs

/**
 * The lyrics in Now Playing. The line being sung is bright and sits a little above the middle;
 * the others are dimmer the further away they are. The list glides to each new line, a tap on a
 * line plays from there, and scrolling by hand looks ahead until the song takes over again a few
 * seconds later. Plain lyrics aren't timed: they start at the top and scroll by hand.
 */
class LyricsPanel(
    private val b: ViewNpLyricsBinding,
    private val onSeek: (Long) -> Unit,
    private val onRetry: () -> Unit,
    private val onWrong: (Long) -> Unit,
    private val onNudge: (Float) -> Unit,
) {
    private val ctx: Context get() = b.root.context
    private val lm = LinearLayoutManager(ctx)
    private val adapter = LinesAdapter()
    private var lyrics: Lyrics? = null
    private var current: Int? = null
    /** Following the song; false for a few seconds after a scroll by hand. */
    private var follow = true
    private var anchorFrac = ANCHOR

    init {
        b.lyricsList.layoutManager = lm
        b.lyricsList.adapter = adapter
        b.lyricsList.quietChanges()
        b.lyricsList.itemAnimator = null
        b.lyricsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        follow = false
                        rv.removeCallbacks(comeBack)
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> if (!follow) {
                        rv.removeCallbacks(comeBack)
                        rv.postDelayed(comeBack, COME_BACK_MS)
                    }
                }
            }
        })
        // The spacers above and below the lines depend on the list's height.
        b.lyricsList.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && adapter.itemCount > 0) {
                b.lyricsList.post {
                    adapter.notifyItemChanged(0)
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                    current?.let { scrollTo(it, smooth = false) }
                }
            }
        }
        b.lyricsEarlier.setOnClickListener { onNudge(-STEP) }
        b.lyricsLater.setOnClickListener { onNudge(STEP) }
        b.lyricsWrong.setOnClickListener { lyrics?.let { onWrong(it.wordsKey()) } }
        b.lyricsRetry.setOnClickListener { onRetry() }
    }

    private val comeBack = Runnable {
        follow = true
        current?.let { scrollTo(it, smooth = true) }
    }

    var visible: Boolean
        get() = b.root.isVisible
        set(v) {
            b.root.isVisible = v
        }

    /** Show what's known about the song playing. */
    fun show(shown: LyricsCenter.Shown?) {
        when (val s = shown?.state) {
            is LyricsState.Found -> {
                status(null)
                setLyrics(s.lyrics, shown.nudged)
            }
            LyricsState.Missing -> status(Status(ctx.getString(R.string.lyrics_none), ctx.getString(R.string.lyrics_none_body), retry = true))
            is LyricsState.Failed -> status(
                if (s.busy) {
                    Status(ctx.getString(R.string.lyrics_busy), ctx.getString(R.string.lyrics_busy_body), retry = true)
                } else {
                    Status(ctx.getString(R.string.lyrics_failed), ctx.getString(R.string.lyrics_failed_body), retry = true)
                },
            )
            else -> status(Status(ctx.getString(R.string.lyrics_looking), "", spinner = true))
        }
    }

    private class Status(val title: String, val body: String, val retry: Boolean = false, val spinner: Boolean = false)

    private fun status(s: Status?) {
        b.lyricsStatus.isVisible = s != null
        if (s == null) return
        lyrics = null
        current = null
        adapter.submit(emptyList(), false)
        b.lyricsBar.visibility = View.INVISIBLE
        b.lyricsSpinner.isVisible = s.spinner
        b.lyricsStatusTitle.setTextIfChanged(s.title)
        b.lyricsStatusBody.setTextIfChanged(s.body)
        b.lyricsStatusBody.isVisible = s.body.isNotEmpty()
        b.lyricsRetry.isVisible = s.retry
    }

    private fun setLyrics(l: Lyrics, nudged: Float) {
        val sameWords = lyrics?.let { it.synced == l.synced && it.lines.size == l.lines.size && it.wordsKey() == l.wordsKey() } == true
        lyrics = l
        b.lyricsBar.visibility = View.VISIBLE
        b.lyricsNote.isVisible = !l.synced
        b.lyricsEarlier.isVisible = l.synced
        b.lyricsLater.isVisible = l.synced
        b.lyricsNudge.isVisible = l.synced && abs(nudged) > 0.01f
        b.lyricsNudge.text = signed(nudged)
        if (sameWords) {
            // Earlier/Later: the same lines at new times; nothing to lay out again.
            adapter.lines = l.lines
            return
        }
        anchorFrac = if (l.synced) ANCHOR else 0f
        current = null
        follow = true
        adapter.submit(l.lines, l.synced)
        lm.scrollToPositionWithOffset(0, 0)
    }

    /**
     * Light the line being sung at [posSec] (the player's position) and keep it in place. Returns
     * how long until something can change, so the caller sleeps until then.
     */
    fun tick(posSec: Float, playing: Boolean): Long {
        val l = lyrics?.takeIf { it.synced } ?: return 1000
        val idx = l.lineAt(posSec + LEAD)
        if (idx != current) {
            val jump = current == null || idx == null || abs(idx - (current ?: 0)) > 3
            current = idx
            adapter.current = idx
            relight(animate = !jump)
            if (follow) scrollTo(idx ?: 0, smooth = !jump || idx == 0)
        }
        if (!playing) return 300
        val next = l.nextStart(idx) ?: return 1000
        return ((next - posSec - LEAD) * 1000f).toLong().coerceIn(16, 1000)
    }

    /** The dimming of every line on screen, after the line being sung changed. */
    private fun relight(animate: Boolean) {
        val rv = b.lyricsList
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(v) as? LinesAdapter.Holder ?: continue
            val a = adapter.alphaFor(holder.index)
            if (animate) v.animate().alpha(a).setDuration(260).start() else v.alpha = a
        }
    }

    /** Glide (or jump) so line [i] sits at the anchor. */
    private fun scrollTo(i: Int, smooth: Boolean) {
        val rv = b.lyricsList
        val pos = i + 1
        if (pos >= adapter.itemCount - 1) return
        val anchor = (rv.height * anchorFrac).toInt()
        val view = lm.findViewByPosition(pos)
        if (smooth && view != null) {
            val s = object : LinearSmoothScroller(ctx) {
                override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int =
                    boxStart + ((boxEnd - boxStart) * anchorFrac).toInt() - (viewStart + viewEnd) / 2

                override fun calculateSpeedPerPixel(dm: DisplayMetrics): Float = 150f / dm.densityDpi
            }
            s.targetPosition = pos
            lm.startSmoothScroll(s)
        } else {
            val h = view?.height ?: ctx.dp(48)
            lm.scrollToPositionWithOffset(pos, anchor - h / 2)
        }
    }

    private fun signed(n: Float): String {
        val s = String.format(Locale.US, "%.2f", abs(n)).trimEnd('0').trimEnd('.')
        return (if (n < 0) "−" else "+") + s + " s"
    }

    /** The lines, with a spacer above and below so the first and last lines can reach the anchor. */
    private inner class LinesAdapter : ListAdapter<Row, LinesAdapter.Holder>(Diff) {
        var current: Int? = null
        var synced = true
        var lines: List<Line> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submit(l: List<Line>, synced: Boolean) {
            this.synced = synced
            lines = l
            current = null
            submitList(if (l.isEmpty()) emptyList() else listOf(Row(-1, "")) + l.mapIndexed { i, line -> Row(i, line.words) } + Row(Int.MAX_VALUE, ""))
        }

        fun alphaFor(i: Int): Float = when {
            i < 0 || i == Int.MAX_VALUE -> 1f
            !synced -> 0.85f
            i == current -> 1f
            else -> maxOf(0.5f - 0.05f * abs(i - (current ?: 0)), 0.3f)
        }

        override fun getItemId(position: Int): Long = getItem(position).index.toLong()

        override fun getItemViewType(position: Int): Int = if (getItem(position).index.let { it < 0 || it == Int.MAX_VALUE }) SPACER else LINE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == SPACER) return Holder(View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(1, 1) })
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false) as TextView
            val h = Holder(tv)
            tv.setOnClickListener {
                val l = lines.getOrNull(h.index) ?: return@setOnClickListener
                if (!synced) return@setOnClickListener
                follow = true
                b.lyricsList.removeCallbacks(comeBack)
                onSeek((l.at * 1000f).toLong())
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = getItem(position)
            h.index = row.index
            val rvH = b.lyricsList.height
            if (h.itemView !is TextView) {
                val top = row.index < 0
                val est = ctx.dp(48)
                val height = when {
                    rvH == 0 -> 1
                    top -> if (synced) maxOf(1, (rvH * ANCHOR).toInt() - est / 2) else ctx.dp(4)
                    else -> (rvH * (1 - ANCHOR)).toInt()
                }
                h.itemView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                return
            }
            val tv = h.itemView as TextView
            tv.text = row.words.ifEmpty { "♪" }
            tv.isClickable = synced
            tv.contentDescription = if (row.words.isEmpty()) "" else null
            h.itemView.alpha = alphaFor(row.index)
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            var index = 0
        }
    }

    private data class Row(val index: Int, val words: String)

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.index == b.index
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }

    companion object {
        /** Light a line up a moment early, the way people read ahead when they sing along. */
        const val LEAD = 0.2f
        /** Earlier / Later moves the lyrics this many seconds. */
        const val STEP = 0.25f
        /** Where the line being sung sits: this far down the list. */
        private const val ANCHOR = 0.42f
        private const val COME_BACK_MS = 3000L
        private const val SPACER = 0
        private const val LINE = 1
    }
}
