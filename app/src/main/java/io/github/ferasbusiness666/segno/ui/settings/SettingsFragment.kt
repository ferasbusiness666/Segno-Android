package io.github.ferasbusiness666.segno.ui.settings

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.ferasbusiness666.segno.BuildConfig
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.backup.Backup
import io.github.ferasbusiness666.segno.data.Settings
import io.github.ferasbusiness666.segno.databinding.FragmentSettingsBinding
import io.github.ferasbusiness666.segno.lyrics.LyricsCenter
import io.github.ferasbusiness666.segno.ui.MainActivity
import io.github.ferasbusiness666.segno.ui.common.Messages
import io.github.ferasbusiness666.segno.ui.common.openUrl
import io.github.ferasbusiness666.segno.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings and About: playback, downloads, updates, licenses, privacy and links. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private var b: FragmentSettingsBinding? = null
    private val main get() = requireActivity() as MainActivity
    private var checking = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSettingsBinding.bind(view)
        this.b = b
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.aboutLine.text = getString(R.string.set_about_line, BuildConfig.VERSION_NAME)
        b.website.setOnClickListener { requireContext().openUrl(WEBSITE) }
        b.source.setOnClickListener { requireContext().openUrl(SOURCE) }
        b.licenses.setOnClickListener { main.push(LicensesFragment()) }

        viewLifecycleOwner.lifecycleScope.launch {
            val (autoplay, autoCheck) = withContext(Dispatchers.IO) {
                Updates.load()
                Settings.syncedLyrics
                Settings.autoplay to Settings.checkUpdates
            }
            b.lyricsSwitch.isChecked = Settings.syncedLyrics
            b.autoplay.isChecked = autoplay
            b.autoCheck.isChecked = autoCheck
            renderUpdate(null)
        }
        b.autoplayRow.setOnClickListener {
            b.autoplay.isChecked = !b.autoplay.isChecked
            val on = b.autoplay.isChecked
            lifecycleScopeIO { Settings.autoplay = on }
        }
        b.lyricsRow.setOnClickListener {
            b.lyricsSwitch.isChecked = !b.lyricsSwitch.isChecked
            val on = b.lyricsSwitch.isChecked
            if (!on) LyricsCenter.clear()
            lifecycleScopeIO { Settings.syncedLyrics = on }
        }
        b.autoCheckRow.setOnClickListener {
            b.autoCheck.isChecked = !b.autoCheck.isChecked
            val on = b.autoCheck.isChecked
            lifecycleScopeIO { Settings.checkUpdates = on }
        }
        b.backupSave.setOnClickListener {
            runCatching { saveFile.launch(Backup.fileName()) }.onFailure { Messages.show(getString(R.string.backup_save_failed)) }
        }
        b.backupRestore.setOnClickListener {
            runCatching { openFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                .onFailure { Messages.show(getString(R.string.backup_read_failed)) }
        }
        b.updateButton.setOnClickListener {
            val found = Updates.available.value
            if (found != null) {
                main.offerUpdate(found)
            } else {
                check()
            }
        }
    }

    // The system's "save as" and "open" screens, so the backup can go to Downloads or Google Drive.
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) main.saveBackup(uri)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) main.restoreBackup(uri)
    }

    private fun lifecycleScopeIO(block: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { block() }
    }

    private fun check() {
        if (checking) return
        checking = true
        b?.updateStatus?.setText(R.string.set_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Updates.check() }
            checking = false
            renderUpdate(r)
            if (r is Updates.Result.Available && b != null) main.offerUpdate(r.release)
        }
    }

    private fun renderUpdate(r: Updates.Result?) {
        val b = b ?: return
        val found = Updates.available.value
        when {
            found != null -> {
                b.updateStatus.text = getString(R.string.set_update_available, found.version)
                b.updateButton.text = getString(R.string.set_get_update, found.version)
            }
            r is Updates.Result.Failed -> {
                b.updateStatus.text = getString(R.string.set_update_failed)
                b.updateButton.text = getString(R.string.try_again)
            }
            r is Updates.Result.UpToDate -> {
                b.updateStatus.text = getString(R.string.set_up_to_date, BuildConfig.VERSION_NAME)
                b.updateButton.text = getString(R.string.set_check_now)
            }
            else -> {
                b.updateStatus.text = getString(R.string.set_version, BuildConfig.VERSION_NAME)
                b.updateButton.text = getString(R.string.set_check_now)
            }
        }
    }

    override fun onDestroyView() {
        b = null
        super.onDestroyView()
    }

    companion object {
        const val WEBSITE = "https://segnomusic.pages.dev"
        const val SOURCE = "https://github.com/ferasbusiness666/Segno-Android"
    }
}
