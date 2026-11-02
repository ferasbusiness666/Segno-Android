package io.github.ferasbusiness666.segno.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.ferasbusiness666.segno.R
import java.io.File

/** Android's answer about an update Segno handed it (see SelfUpdate.install). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.i("Segno", "Update install: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
            file?.delete()
        }
        when (status) {
            // Android asks first ("Do you want to update this app?"); shown once Segno is on screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                SelfUpdate.needsConfirm(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
            // Segno restarts as the new version, so there's rarely anyone left to tell.
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.finished(context.getString(R.string.upd_done))
            PackageInstaller.STATUS_FAILURE_ABORTED -> SelfUpdate.finished(context.getString(R.string.upd_aborted))
            PackageInstaller.STATUS_FAILURE_STORAGE -> SelfUpdate.finished(context.getString(R.string.upd_no_space))
            PackageInstaller.STATUS_FAILURE_BLOCKED -> SelfUpdate.finished(context.getString(R.string.upd_blocked))
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            -> SelfUpdate.finished(context.getString(R.string.upd_conflict))
            PackageInstaller.STATUS_FAILURE_INVALID -> SelfUpdate.finished(context.getString(R.string.upd_unreadable))
            else -> SelfUpdate.finished(context.getString(R.string.upd_install_failed))
        }
    }

    companion object {
        const val ACTION = "io.github.ferasbusiness666.segno.UPDATE_STATUS"
        const val EXTRA_FILE = "file"
    }
}
