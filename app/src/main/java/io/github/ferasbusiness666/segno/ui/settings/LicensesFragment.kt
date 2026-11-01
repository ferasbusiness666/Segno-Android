package io.github.ferasbusiness666.segno.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.databinding.FragmentLicensesBinding
import io.github.ferasbusiness666.segno.databinding.ItemLicenseBinding
import io.github.ferasbusiness666.segno.ui.common.dp
import io.github.ferasbusiness666.segno.ui.common.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open-source projects inside Segno, with their licenses. */
class LicensesFragment : Fragment(R.layout.fragment_licenses) {
    private class Entry(val name: String, val by: String, val license: String, val url: String, val text: Int)

    private val entries = listOf(
        Entry("Segno for Android", "Feras Hania", "GPL-3.0", "https://github.com/ferasbusiness666/segno-android", R.raw.license_gpl3),
        Entry("NewPipe Extractor", "Team NewPipe", "GPL-3.0-or-later", "https://github.com/TeamNewPipe/NewPipeExtractor", R.raw.license_gpl3),
        Entry("nanojson", "The nanojson Authors", "Apache-2.0", "https://github.com/TeamNewPipe/nanojson", R.raw.license_apache2),
        Entry("AndroidX Media3 (ExoPlayer)", "The Android Open Source Project", "Apache-2.0", "https://github.com/androidx/media", R.raw.license_apache2),
        Entry("AndroidX libraries", "The Android Open Source Project", "Apache-2.0", "https://developer.android.com/jetpack/androidx", R.raw.license_apache2),
        Entry("Material Components for Android", "Google", "Apache-2.0", "https://github.com/material-components/material-components-android", R.raw.license_apache2),
        Entry("Guava", "Google", "Apache-2.0", "https://github.com/google/guava", R.raw.license_apache2),
        Entry("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines", R.raw.license_apache2),
        Entry("OkHttp and Okio", "Square", "Apache-2.0", "https://github.com/square/okhttp", R.raw.license_apache2),
        Entry("Coil", "Coil Contributors", "Apache-2.0", "https://github.com/coil-kt/coil", R.raw.license_apache2),
        Entry("JSR 305 annotations", "FindBugs", "Apache-2.0", "https://code.google.com/archive/p/jsr-305/", R.raw.license_apache2),
        Entry("Rhino", "Mozilla", "MPL-2.0", "https://github.com/mozilla/rhino", R.raw.license_mpl2),
        Entry("jsoup", "Jonathan Hedley", "MIT", "https://jsoup.org", R.raw.license_mit_jsoup),
        Entry("Protocol Buffers", "Google", "BSD-3-Clause", "https://github.com/protocolbuffers/protobuf", R.raw.license_bsd3_protobuf),
        Entry("Core library desugaring", "Google, Oracle", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs", R.raw.license_classpath),
        Entry("Inter typeface", "The Inter Project Authors", "SIL OFL 1.1", "https://github.com/rsms/inter", R.raw.license_ofl),
        Entry("Lucide icons", "Lucide Contributors", "ISC", "https://lucide.dev", R.raw.license_isc_lucide),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentLicensesBinding.bind(view)
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        val inflater = LayoutInflater.from(requireContext())
        for (e in entries) {
            val row = ItemLicenseBinding.inflate(inflater, b.list, false)
            row.licName.text = e.name
            row.licSub.text = "${e.by} · ${e.license}"
            row.root.setOnClickListener { showText(e) }
            row.licLink.setOnClickListener { requireContext().openUrl(e.url) }
            b.list.addView(row.root)
        }
    }

    private fun showText(e: Entry) {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                resources.openRawResource(e.text).bufferedReader().use { it.readText() }
            }
            val ctx = requireContext()
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                setTextIsSelectable(true)
                val p = ctx.dp(24)
                setPadding(p, ctx.dp(8), p, ctx.dp(8))
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(e.name)
                .setView(ScrollView(ctx).apply { addView(tv) })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }
}
