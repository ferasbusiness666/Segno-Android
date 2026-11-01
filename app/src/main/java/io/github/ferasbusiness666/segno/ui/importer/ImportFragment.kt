package io.github.ferasbusiness666.segno.ui.importer

import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Format
import io.github.ferasbusiness666.segno.databinding.FragmentImportBinding
import io.github.ferasbusiness666.segno.databinding.ItemFoundBinding
import io.github.ferasbusiness666.segno.databinding.ItemStepBinding
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.Images
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.whileStarted
import io.github.ferasbusiness666.segno.ui.importer.ImportViewModel.Stage
import kotlinx.coroutines.launch

/** Import playlists from Spotify and YouTube links, many at once. */
class ImportFragment : Fragment(R.layout.fragment_import) {
    private var b: FragmentImportBinding? = null
    private val vm: ImportViewModel by viewModels()
    private val main get() = requireActivity() as MainActivity
    private var help = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentImportBinding.bind(view)
        this.b = b
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        if (savedInstanceState == null) {
            arguments?.getString(TEXT)?.let {
                b.paste.setText(it)
                vm.look(it)
            }
        }
        b.find.setOnClickListener { vm.look(b.paste.text.toString()) }
        b.pasteButton.setOnClickListener { pasteFromClipboard() }
        b.clear.setOnClickListener {
            b.paste.setText("")
            vm.reset()
        }
        b.paste.doAfterTextChanged { updateButtons() }
        val chips = listOf(b.helpSpotify, b.helpYouTube, b.helpMusic)
        chips.forEachIndexed { i, c -> c.setOnClickListener { showHelp(i) } }
        showHelp(help)
        updateButtons()
        whileStarted { launch { vm.stage.collect(::render) } }
    }

    private fun updateButtons() {
        val b = b ?: return
        val busy = vm.stage.value is Stage.Looking || vm.stage.value is Stage.Importing
        val has = b.paste.text.isNotBlank()
        b.find.isEnabled = has && !busy
        b.find.alpha = if (b.find.isEnabled) 1f else 0.5f
        b.clear.isVisible = has && !busy
    }

    private fun pasteFromClipboard() {
        val b = b ?: return
        val clip = requireContext().getSystemService<ClipboardManager>()?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isBlank()) {
            Messages.show(getString(R.string.import_clipboard_empty))
            return
        }
        val now = b.paste.text.toString()
        b.paste.setText(if (now.isBlank()) text else now.trimEnd() + "\n" + text)
        b.paste.setSelection(b.paste.text.length)
    }

    private fun showHelp(i: Int) {
        val b = b ?: return
        help = i
        listOf(b.helpSpotify, b.helpYouTube, b.helpMusic).forEachIndexed { k, c -> c.isSelected = k == i }
        val steps = when (i) {
            0 -> listOf(
                R.string.import_sp_one to R.string.import_sp_one_how,
                R.string.import_sp_many to R.string.import_sp_many_how,
                R.string.import_sp_liked to R.string.import_sp_liked_how,
            )
            1 -> listOf(R.string.import_yt_one to R.string.import_yt_one_how, R.string.import_yt_all to R.string.import_yt_all_how)
            else -> listOf(R.string.import_ytm_one to R.string.import_ytm_one_how, R.string.import_ytm_all to R.string.import_ytm_all_how)
        }
        b.steps.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for ((what, how) in steps) {
            val s = ItemStepBinding.inflate(inflater, b.steps, false)
            s.stepWhat.setText(what)
            s.stepHow.setText(how)
            b.steps.addView(s.root)
        }
    }

    private fun render(stage: Stage) {
        val b = b ?: return
        updateButtons()
        val box = b.results
        box.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        when (stage) {
            Stage.Start -> Unit
            is Stage.Looking -> box.addView(busyRow(stage.note))
            is Stage.Importing -> box.addView(busyRow(getString(R.string.import_importing, (stage.done + 1).coerceAtMost(stage.total), stage.total)))
            is Stage.Pick -> {
                if (stage.found.isNotEmpty()) {
                    val picked = stage.found.count { it.picked }
                    val all = picked == stage.found.size
                    box.addView(header(getString(R.string.import_found, Format.plural(stage.found.size, "playlist", "playlists")), getString(if (all) R.string.import_select_none else R.string.import_select_all)) { vm.setAll(!all) })
                    stage.found.forEachIndexed { k, f ->
                        val row = ItemFoundBinding.inflate(inflater, box, false)
                        row.foundCheck.isChecked = f.picked
                        row.foundTitle.text = f.info.title
                        val parts = mutableListOf(f.info.site)
                        f.songs?.let { parts += Format.plural(it.size, "song", "songs") }
                        if (f.info.owner.isNotEmpty()) parts += f.info.owner
                        if (f.info.url in stage.already) parts += getString(R.string.import_already)
                        row.foundSub.text = parts.joinToString(" · ")
                        Images.url(row.foundArt, Images.sized(f.info.cover, requireContext().dp(144)), R.drawable.art_placeholder_playlist)
                        row.root.setOnClickListener { vm.toggle(k) }
                        box.addView(row.root)
                    }
                    val go = inflater.inflate(R.layout.item_pill_primary, box, false) as TextView
                    go.text = getString(R.string.import_go, Format.plural(picked, "playlist", "playlists"))
                    go.isEnabled = picked > 0
                    go.alpha = if (picked > 0) 1f else 0.5f
                    go.setOnClickListener { vm.import() }
                    box.addView(go)
                }
                problems(box, stage.problems)
            }
            is Stage.Done -> {
                box.addView(header(vm.summary(stage), null, null))
                if (stage.playlists > 0) {
                    box.addView(text(getString(R.string.import_done_where)))
                    val open = inflater.inflate(R.layout.item_pill_primary, box, false) as TextView
                    open.setText(R.string.lib_playlists)
                    open.setOnClickListener { main.openLibrary() }
                    box.addView(open)
                }
                val more = inflater.inflate(R.layout.item_pill_secondary, box, false) as TextView
                more.setText(R.string.import_more)
                more.setOnClickListener {
                    b.paste.setText("")
                    vm.reset()
                }
                box.addView(more)
                problems(box, stage.problems)
            }
        }
    }

    private fun busyRow(note: String): View {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.minimumHeight = requireContext().dp(48)
        val p = ProgressBar(requireContext())
        p.indeterminateTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.accent)
        row.addView(p, LinearLayout.LayoutParams(requireContext().dp(22), requireContext().dp(22)))
        val t = text(note)
        (t.layoutParams as? LinearLayout.LayoutParams)?.marginStart = requireContext().dp(12)
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = requireContext().dp(12) })
        return row
    }

    private fun header(title: String, action: String?, onAction: (() -> Unit)?): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_section, b!!.results, false)
        v.setPadding(0, v.paddingTop, 0, v.paddingBottom)
        v.findViewById<TextView>(R.id.sectionTitle).text = title
        v.findViewById<TextView>(R.id.sectionAction).apply {
            isVisible = action != null
            text = action
            setOnClickListener { onAction?.invoke() }
        }
        return v
    }

    private fun text(s: String): TextView = TextView(requireContext()).apply {
        text = s
        setTextAppearance(R.style.TextAppearance_Segno_Body)
        setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun problems(box: LinearLayout, list: List<String>) {
        val inflater = LayoutInflater.from(requireContext())
        for (p in list) {
            val t = inflater.inflate(R.layout.item_problem, box, false) as TextView
            t.text = p
            box.addView(t)
        }
    }

    override fun onDestroyView() {
        b = null
        super.onDestroyView()
    }

    companion object {
        private const val TEXT = "text"

        fun newInstance(text: String?) = ImportFragment().apply {
            if (text != null) arguments = bundleOf(TEXT to text)
        }
    }
}
