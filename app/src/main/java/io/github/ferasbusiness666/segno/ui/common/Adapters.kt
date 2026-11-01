package io.github.ferasbusiness666.segno.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.databinding.ItemCardBinding
import io.github.ferasbusiness666.segno.databinding.ItemRowBinding
import io.github.ferasbusiness666.segno.databinding.ItemSectionBinding

/**
 * A row that opens something: a playlist, an album, an artist, or a fixed entry like Loved
 * songs. [icon] on a [tile] color draws a colored square with an icon instead of a picture.
 */
class Row(
    val key: String,
    val title: String,
    val sub: String,
    val art: String = "",
    val icon: Int = 0,
    val tile: Int = 0,
    val circle: Boolean = false,
    val placeholder: Int = R.drawable.art_placeholder_playlist,
    val onClick: () -> Unit,
) {
    fun sameContent(o: Row) = title == o.title && sub == o.sub && art == o.art && icon == o.icon && tile == o.tile && circle == o.circle
}

class RowAdapter : ListAdapter<Row, RowAdapter.Holder>(Diff) {
    private var artPx = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (artPx == 0) artPx = parent.resources.getDimensionPixelSize(R.dimen.row_art) * 2
        val h = Holder(b)
        b.root.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) getItem(p).onClick()
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = getItem(position)
        val b = h.b
        b.rowTitle.text = r.title
        b.rowSub.text = r.sub
        b.rowSub.isVisible = r.sub.isNotEmpty()
        b.rowArt.circle = r.circle
        if (r.icon != 0) {
            b.rowArt.dispose()
            b.rowArt.setImageDrawable(null)
            b.rowArt.setBackgroundResource(if (r.tile != 0) r.tile else R.color.raised_2)
            b.rowIcon.isVisible = true
            b.rowIcon.setImageResource(r.icon)
        } else {
            b.rowArt.background = null
            b.rowIcon.isVisible = false
            Images.url(b.rowArt, Images.sized(r.art, artPx), r.placeholder)
        }
    }

    class Holder(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row) = a.sameContent(b)
    }
}

/** One fixed view (a header, a banner) that can be shown or hidden inside a ConcatAdapter. */
class ViewAdapter(
    private val layout: Int,
    private val stableId: Long,
    private val bind: (View) -> Unit = {},
) : RecyclerView.Adapter<ViewAdapter.Holder>() {
    var visible = true
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    init {
        setHasStableIds(true)
    }

    fun refresh() {
        if (visible) notifyItemChanged(0)
    }

    override fun getItemId(position: Int) = stableId
    override fun getItemCount() = if (visible) 1 else 0
    override fun getItemViewType(position: Int) = layout

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.itemView)

    class Holder(v: View) : RecyclerView.ViewHolder(v)
}

/** A section title, with an optional action on the right ("See all"). */
class SectionAdapter(private val stableId: Long, private val small: Boolean = false) : RecyclerView.Adapter<SectionAdapter.Holder>() {
    private var title: String = ""
    private var action: String? = null
    private var onAction: (() -> Unit)? = null
    private var shown = false

    init {
        setHasStableIds(true)
    }

    fun set(title: String?, action: String? = null, onAction: (() -> Unit)? = null) {
        val was = shown
        shown = title != null
        this.title = title.orEmpty()
        this.action = action
        this.onAction = onAction
        when {
            was && shown -> notifyItemChanged(0)
            was -> notifyItemRemoved(0)
            shown -> notifyItemInserted(0)
        }
    }

    override fun getItemId(position: Int) = stableId
    override fun getItemCount() = if (shown) 1 else 0
    override fun getItemViewType(position: Int) = R.layout.item_section

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        if (small) {
            h.b.sectionTitle.setTextAppearance(R.style.TextAppearance_Segno_SmallStrong)
            h.b.sectionTitle.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.context, R.color.text_2))
            h.b.root.minimumHeight = 0
        }
        h.b.sectionTitle.text = title
        h.b.sectionAction.isVisible = action != null
        h.b.sectionAction.text = action
        h.b.sectionAction.setOnClickListener { onAction?.invoke() }
    }

    class Holder(val b: ItemSectionBinding) : RecyclerView.ViewHolder(b.root)
}

/** Song cards in a sideways row. */
class CardAdapter(
    private val onClick: (Int, Track) -> Unit,
    private val onLong: (Track) -> Unit,
) : ListAdapter<Track, CardAdapter.Holder>(TrackAdapter.Diff) {
    private var artPx = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (artPx == 0) artPx = parent.context.dp(272)
        val h = Holder(b)
        b.root.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, getItem(p))
        }
        b.root.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(getItem(p))
            true
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val t = getItem(position)
        h.b.cardTitle.text = t.title
        h.b.cardSub.text = t.artist
        Images.track(h.b.cardArt, t, artPx)
    }

    class Holder(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)
}
