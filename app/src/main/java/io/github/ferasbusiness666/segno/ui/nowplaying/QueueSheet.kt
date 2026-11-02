package io.github.ferasbusiness666.segno.ui.nowplaying

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.ItemTrackBinding
import io.github.ferasbusiness666.segno.databinding.SheetQueueBinding
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.PlayerHub
import io.github.ferasbusiness666.segno.ui.common.QueueEntry
import io.github.ferasbusiness666.segno.ui.common.SectionAdapter
import io.github.ferasbusiness666.segno.ui.common.SongActions
import io.github.ferasbusiness666.segno.ui.common.ViewAdapter
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** The queue as a bottom sheet: the song playing, then what's next. Drag to reorder, swipe to remove. */
class QueueSheet : BottomSheetDialogFragment() {
    private var b: SheetQueueBinding? = null
    private val nowTitle = SectionAdapter(1, small = true)
    private val nextTitle = SectionAdapter(2, small = true)
    private lateinit var now: QueueAdapter
    private lateinit var next: QueueAdapter
    private lateinit var emptyNote: ViewAdapter
    private var touch: ItemTouchHelper? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        d.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        d.behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        return d
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetQueueBinding.inflate(inflater, container, false)
        this.b = b
        // Fill the sheet, so the list scrolls inside it.
        b.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = b ?: return
        now = QueueAdapter(draggable = false, onClick = { }, onMore = ::menu, onStartDrag = null)
        next = QueueAdapter(draggable = true, onClick = { e -> PlayerHub.playAt(e.index) }, onMore = ::menu, onStartDrag = { h -> touch?.startDrag(h) })
        emptyNote = ViewAdapter(R.layout.item_queue_note, 3) { v ->
            (v as android.widget.TextView).setText(if (Settings.autoplay) R.string.queue_empty else R.string.queue_empty_off)
        }.apply { visible = false }
        val config = ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build()
        b.queueList.layoutManager = LinearLayoutManager(requireContext())
        b.queueList.adapter = ConcatAdapter(config, nowTitle, now, nextTitle, next, emptyNote)
        touch = ItemTouchHelper(Callback()).also { it.attachToRecyclerView(b.queueList) }
        ViewCompat.setOnApplyWindowInsetsListener(b.queueList) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + v.resources.getDimensionPixelSize(R.dimen.s4))
            insets
        }
        whileStarted {
            launch {
                PlayerHub.state.distinctUntilChangedBy { it.queueVersion to it.track?.id }.collect { load() }
            }
        }
    }

    private fun load() {
        val (cur, upcoming) = PlayerHub.queue()
        nowTitle.set(if (cur != null) getString(R.string.queue_now) else null)
        now.submit(listOfNotNull(cur))
        nextTitle.set(getString(R.string.queue_next), if (upcoming.isNotEmpty()) getString(R.string.queue_clear) else null) {
            PlayerHub.clearUpcoming()
        }
        next.submit(upcoming)
        emptyNote.visible = upcoming.isEmpty()
    }

    private fun menu(e: QueueEntry) {
        val extra = if (e.index != PlayerHub.queue().first?.index) {
            listOf(
                SongActions.Item(R.drawable.ic_close, getString(R.string.queue_remove)) {
                    PlayerHub.remove(e.index)
                },
            )
        } else {
            emptyList()
        }
        SongActions.menu(requireActivity(), e.track, extra = extra)
    }

    private inner class Callback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END) {
        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (vh is QueueAdapter.Holder && vh.bindingAdapter === next) super.getMovementFlags(rv, vh) else 0

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            if (to !is QueueAdapter.Holder || to.bindingAdapter !== next) return false
            val a = from.bindingAdapterPosition
            val c = to.bindingAdapterPosition
            if (a < 0 || c < 0) return false
            next.dragMove(a, c)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val p = vh.bindingAdapterPosition
            val e = next.items.getOrNull(p)
            if (e == null) {
                next.notifyItemChanged(p.coerceAtLeast(0))
                return
            }
            PlayerHub.remove(e.index)
            Messages.show(getString(R.string.queue_removed))
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            val moved = next.dragEnd() ?: return
            PlayerHub.moveUpcoming(moved.first, moved.second)
        }
    }

    override fun onDestroyView() {
        b?.queueList?.adapter = null
        b = null
        super.onDestroyView()
    }

    /** Queue rows. Keys come from the player, so the same song twice is still two rows. */
    class QueueAdapter(
        private val draggable: Boolean,
        private val onClick: (QueueEntry) -> Unit,
        private val onMore: (QueueEntry) -> Unit,
        private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)?,
    ) : RecyclerView.Adapter<QueueAdapter.Holder>() {
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
        private var dragList: MutableList<QueueEntry>? = null
        private var dragFrom = -1
        private var dragTo = -1
        private var artPx = 0

        val items: List<QueueEntry> get() = dragList ?: differ.currentList

        init {
            setHasStableIds(true)
        }

        fun submit(list: List<QueueEntry>) {
            if (dragList == null) differ.submitList(list)
        }

        fun dragMove(from: Int, to: Int) {
            val l = dragList ?: differ.currentList.toMutableList().also {
                dragList = it
                dragFrom = from
            }
            if (from !in l.indices || to !in l.indices) return
            l.add(to, l.removeAt(from))
            dragTo = to
            notifyItemMoved(from, to)
        }

        /** (from, to) of the finished drag, in the upcoming list. */
        fun dragEnd(): Pair<Int, Int>? {
            val l = dragList ?: return null
            val result = dragFrom to dragTo
            muted = true
            differ.submitList(ArrayList(l)) {
                muted = false
                dragList = null
            }
            return if (result.first >= 0 && result.second >= 0 && result.first != result.second) result else null
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].uid

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            if (artPx == 0) artPx = parent.resources.getDimensionPixelSize(R.dimen.row_art) * 2
            val h = Holder(b)
            b.root.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
            b.more.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onMore(items[p])
            }
            if (draggable) {
                b.drag.isVisible = true
                b.drag.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag?.invoke(h)
                    false
                }
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = items[position]
            val ctx = h.b.root.context
            h.b.title.text = e.track.title
            if (titleColor == 0) titleColor = ContextCompat.getColor(ctx, if (draggable) R.color.text else R.color.accent)
            h.b.title.setTextColor(titleColor)
            h.b.sub.text = Format.sub(e.track.artist, if (e.track.durationMs > 0) Format.time(e.track.durationMs) else null)
            Images.track(h.b.art, e.track, artPx)
        }

        private var titleColor = 0

        class Holder(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

        object Diff : DiffUtil.ItemCallback<QueueEntry>() {
            override fun areItemsTheSame(a: QueueEntry, b: QueueEntry) = a.uid == b.uid
            override fun areContentsTheSame(a: QueueEntry, b: QueueEntry) = a.track == b.track
        }
    }
}
