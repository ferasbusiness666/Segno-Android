package io.github.ferasbusiness666.segno.ui.common

import android.Manifest
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.data.Loved
import io.github.ferasbusiness666.segno.data.Playlists
import io.github.ferasbusiness666.segno.databinding.SheetMenuBinding
import io.github.ferasbusiness666.segno.download.Downloads
import io.github.ferasbusiness666.segno.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What can be done with songs, from any screen. */
object SongActions {
    class Item(val icon: Int, val label: String, val action: () -> Unit)

    /** The ⋮ menu of a song. [playlistId]: the playlist it's shown in, to offer removing it. */
    fun menu(activity: FragmentActivity, track: Track, playlistId: Long? = null, extra: List<Item> = emptyList()) {
        activity.lifecycleScope.launch {
            val loved = withContext(Dispatchers.IO) { Loved.isLoved(track.id) }
            val dl = Downloads.state(track.id)
            val items = ArrayList<Item>()
            items += Item(R.drawable.ic_play_next, activity.getString(R.string.menu_play_next)) {
                PlayerHub.playNext(activity, listOf(track))
                Messages.show(activity.getString(R.string.toast_play_next))
            }
            items += Item(R.drawable.ic_add_to_queue, activity.getString(R.string.menu_add_to_queue)) {
                PlayerHub.addToQueue(activity, listOf(track))
                Messages.show(activity.getString(R.string.toast_added_queue))
            }
            items += Item(if (loved) R.drawable.ic_heart_filled else R.drawable.ic_heart, activity.getString(if (loved) R.string.menu_unlove else R.string.menu_love)) {
                toggleLove(activity, track)
            }
            items += Item(R.drawable.ic_list_plus, activity.getString(R.string.menu_add_to_playlist)) { addToPlaylist(activity, listOf(track)) }
            if (track.isOnline) {
                if (dl?.isDone == true) {
                    items += Item(R.drawable.ic_trash, activity.getString(R.string.menu_remove_download)) { removeDownload(activity, track) }
                } else if (dl?.isActive != true) {
                    items += Item(R.drawable.ic_download, activity.getString(R.string.menu_download)) { download(activity, listOf(track)) }
                }
            }
            if (playlistId != null) {
                items += Item(R.drawable.ic_close, activity.getString(R.string.menu_remove_from_playlist)) {
                    activity.lifecycleScope.launch(Dispatchers.IO) { Playlists.remove(playlistId, track.id) }
                }
            }
            items += extra
            if (track.kind == Track.Kind.Local && track.albumId != 0L && track.album.isNotEmpty()) {
                items += Item(R.drawable.ic_album, activity.getString(R.string.menu_go_to_album)) {
                    (activity as? MainActivity)?.openAlbum(track.albumId, track.album)
                }
            }
            if (track.isOnline) {
                val site = if (track.kind == Track.Kind.Spotify) R.string.menu_open_spotify else R.string.menu_open_youtube
                items += Item(R.drawable.ic_external, activity.getString(site)) { activity.openUrl(track.pageUrl) }
                items += Item(R.drawable.ic_link, activity.getString(R.string.menu_share)) {
                    activity.shareText(Format.sub(track.title, track.artist) + "\n" + track.pageUrl)
                }
            }
            sheet(activity, track.title, Format.sub(track.artist, if (track.durationMs > 0) Format.time(track.durationMs) else null), track, items)
        }
    }

    /** A bottom sheet with a header and a list of actions. */
    fun sheet(activity: FragmentActivity, title: String, sub: String, art: Track?, items: List<Item>, artUrl: String? = null) {
        val dialog = BottomSheetDialog(activity)
        val b = SheetMenuBinding.inflate(LayoutInflater.from(activity))
        b.title.text = title
        b.sub.text = sub
        b.sub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        val px = activity.dp(96)
        when {
            art != null -> Images.track(b.art, art, px)
            artUrl != null -> Images.url(b.art, Images.sized(artUrl, px), R.drawable.art_placeholder_playlist)
            else -> b.art.visibility = View.GONE
        }
        val tint = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_2))
        for (item in items) {
            val row = LayoutInflater.from(activity).inflate(R.layout.item_menu, b.items, false) as TextView
            row.text = item.label
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(item.icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(row, tint)
            row.setOnClickListener {
                dialog.dismiss()
                item.action()
            }
            b.items.addView(row)
        }
        dialog.setContentView(b.root)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    fun toggleLove(activity: FragmentActivity, track: Track) {
        activity.lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { Loved.toggle(track) }
            Messages.show(activity.getString(if (now) R.string.toast_loved else R.string.toast_unloved))
        }
    }

    fun download(activity: FragmentActivity, tracks: List<Track>) {
        val online = tracks.filter { it.isOnline }
        if (online.isEmpty()) return
        (activity as? MainActivity)?.askNotifications()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            (activity as? MainActivity)?.askStorage { download(activity, tracks) }
            return
        }
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { Downloads.enqueue(app, online) }
            Messages.show(
                when {
                    n == 0 -> activity.getString(R.string.toast_already_downloaded)
                    n == 1 -> activity.getString(R.string.toast_downloading)
                    else -> activity.getString(R.string.toast_downloading_n, n)
                },
            )
        }
    }

    fun removeDownload(activity: FragmentActivity, track: Track) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { Downloads.delete(app, track.id) }
            Messages.show(activity.getString(R.string.toast_download_removed))
        }
    }

    /** Picks a playlist (or makes a new one) and adds [tracks] to it. */
    fun addToPlaylist(activity: FragmentActivity, tracks: List<Track>) {
        activity.lifecycleScope.launch {
            val lists = withContext(Dispatchers.IO) { Playlists.all() }
            val items = ArrayList<Item>()
            items += Item(R.drawable.ic_plus, activity.getString(R.string.new_playlist)) { newPlaylist(activity, tracks) }
            for (p in lists) {
                items += Item(R.drawable.ic_playlist, p.name) {
                    activity.lifecycleScope.launch {
                        val added = withContext(Dispatchers.IO) { Playlists.add(p.id, tracks) }
                        Messages.show(activity.getString(if (added > 0) R.string.toast_added_to else R.string.toast_already_in, p.name))
                    }
                }
            }
            val one = tracks.singleOrNull()
            sheet(
                activity,
                activity.getString(R.string.menu_add_to_playlist),
                one?.title ?: activity.resources.getQuantityString(R.plurals.songs, tracks.size, tracks.size),
                null,
                items,
            )
        }
    }

    /** Asks for a name and makes a playlist, with [tracks] in it. */
    fun newPlaylist(activity: FragmentActivity, tracks: List<Track> = emptyList(), onMade: ((Long) -> Unit)? = null) {
        nameDialog(activity, activity.getString(R.string.new_playlist), "") { name ->
            activity.lifecycleScope.launch {
                val id = withContext(Dispatchers.IO) { Playlists.create(name, tracks) }
                Messages.show(activity.getString(R.string.toast_playlist_made, name))
                onMade?.invoke(id)
            }
        }
    }

    fun nameDialog(activity: FragmentActivity, title: String, start: String, onOk: (String) -> Unit) {
        val box = FrameLayout(activity)
        val input = EditText(activity).apply {
            setText(start)
            setSelection(start.length)
            hint = activity.getString(R.string.new_playlist_hint)
            setSingleLine()
            setTextColor(ContextCompat.getColor(activity, R.color.text))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_3))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent))
        }
        val pad = activity.dp(24)
        box.setPadding(pad, activity.dp(8), pad, 0)
        box.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            input.requestFocus()
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        d.show()
    }
}
