package io.github.ferasbusiness666.segno.ui.common

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.databinding.ItemTrackBinding
import io.github.ferasbusiness666.segno.download.DownloadState

/**
 * Song rows: cover, title, artist and length, and a menu button. The song playing has an accent
 * title; downloads show their state under the title. Updates only the rows that change.
 */
class TrackAdapter(
    private val onClick: (index: Int, track: Track) -> Unit,
    private val onMore: (index: Int, track: Track) -> Unit,
    /** Shows the drag handle; [onStartDrag] is called when it's touched. */
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    /** While a drag settles, the list changes without moving rows again. */
    private var muted = false
    private val differ = AsyncListDiffer(
        object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { if (!muted) notifyItemRangeInserted(position, count) }
            override fun onRemoved(position: Int, count: Int) { if (!muted) notifyItemRangeRemoved(position, count) }
            override fun onMoved(fromPosition: Int, toPosition: Int) { if (!muted) notifyItemMoved(fromPosition, toPosition) }
            override fun onChanged(position: Int, count: Int, payload: Any?) { if (!muted) notifyItemRangeChanged(position, count, payload) }
        },
        AsyncDifferConfig.Builder(Diff).build(),
    )
    private var dragList: MutableList<Track>? = null

    val currentList: List<Track> get() = dragList ?: differ.currentList

    fun submitList(list: List<Track>?) = differ.submitList(list)

    fun getItem(position: Int): Track = currentList[position]

    override fun getItemCount() = currentList.size

    /** Moves a row while it's dragged. */
    fun dragMove(from: Int, to: Int) {
        val l = dragList ?: differ.currentList.toMutableList().also { dragList = it }
        if (from !in l.indices || to !in l.indices) return
        l.add(to, l.removeAt(from))
        notifyItemMoved(from, to)
    }

    /** The order after a drag, or null if nothing moved. */
    fun dragEnd(): List<Track>? {
        val l = dragList ?: return null
        muted = true
        differ.submitList(ArrayList(l)) {
            muted = false
            dragList = null
        }
        return l
    }

    private var nowId: String? = null
    private var downloads: Map<String, DownloadState> = emptyMap()
    private var artPx = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    /** Marks the song playing. */
    fun setNow(id: String?) {
        if (id == nowId) return
        val old = nowId
        nowId = id
        for (i in 0 until itemCount) {
            val t = getItem(i).id
            if (t == old || t == id) notifyItemChanged(i, PAYLOAD_STATE)
        }
    }

    fun setDownloads(map: Map<String, DownloadState>) {
        val old = downloads
        downloads = map
        if (old === map) return
        for (i in 0 until itemCount) {
            val id = getItem(i).id
            if (old[id] != map[id]) notifyItemChanged(i, PAYLOAD_STATE)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (artPx == 0) artPx = parent.resources.getDimensionPixelSize(R.dimen.row_art) * 2
        val h = Holder(b)
        b.root.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val t = getItem(pos)
            if (t.isMissing) Messages.show(it.context.getString(R.string.missing_song)) else onClick(pos, t)
        }
        b.more.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onMore(pos, getItem(pos))
        }
        b.root.setOnLongClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onMore(pos, getItem(pos))
            true
        }
        if (onStartDrag != null) {
            b.drag.isVisible = true
            b.drag.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag.invoke(h)
                false
            }
        }
        return h
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = getItem(position)
        holder.b.title.text = t.title
        Images.track(holder.b.art, t, artPx)
        bindState(holder, t)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
            bindState(holder, getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindState(holder: Holder, t: Track) {
        val b = holder.b
        val ctx = b.root.context
        if (accent == 0) {
            accent = ContextCompat.getColor(ctx, R.color.accent)
            text = ContextCompat.getColor(ctx, R.color.text)
            unknown = ctx.getString(R.string.unknown_artist)
        }
        b.title.setTextColor(if (t.id == nowId) accent else text)
        val d = downloads[t.id]
        val artist = t.artist.ifEmpty { unknown }
        val length = if (t.durationMs > 0) Format.time(t.durationMs) else null
        val source = if (t.kind == Track.Kind.Spotify) ctx.getString(R.string.from_spotify) else null
        var icon = 0
        b.sub.text = when {
            t.isMissing -> {
                icon = R.drawable.ic_alert_small
                Format.sub(artist, ctx.getString(R.string.missing_short))
            }
            d == null -> Format.sub(artist, length, source)
            d.isDone -> {
                icon = R.drawable.ic_downloaded_small
                Format.sub(artist, length)
            }
            d.state == DownloadState.RUNNING -> {
                icon = R.drawable.ic_download_small
                ctx.getString(R.string.state_downloading, (d.progress * 100).toInt())
            }
            d.state == DownloadState.WAITING -> {
                icon = R.drawable.ic_download_small
                ctx.getString(R.string.state_waiting)
            }
            else -> {
                icon = R.drawable.ic_alert_small
                ctx.getString(R.string.state_failed, d.error)
            }
        }
        // Setting the small icon lays the row out again, so only when it changes.
        if (holder.subIcon != icon) {
            holder.subIcon = icon
            b.sub.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
    }

    private var accent = 0
    private var text = 0
    private var unknown = ""

    class Holder(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root) {
        var subIcon = 0
    }

    object Diff : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(a: Track, b: Track) = a.id == b.id
        override fun areContentsTheSame(a: Track, b: Track) = a == b
    }

    companion object {
        private const val PAYLOAD_STATE = "state"
    }
}
