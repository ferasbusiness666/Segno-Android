package io.github.ferasbusiness666.segno.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.github.ferasbusiness666.segno.BuildConfig
import io.github.ferasbusiness666.segno.R
import io.github.ferasbusiness666.segno.core.Net
import io.github.ferasbusiness666.segno.ui.common.Messages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Updating Segno from inside the app: downloads the release's APK, checks it is Segno, signed
 * with the same key and newer (see [ApkCheck]), then hands it to Android's PackageInstaller.
 * Android shows its own "Install this update?" question unless Segno installed itself before.
 * Debug builds never install release APKs: they open the release page instead.
 */
object SelfUpdate {
    sealed interface State {
        data object Idle : State
        /** [percent]: -1 while the size isn't known. */
        data class Downloading(val version: String, val percent: Int) : State
        data class Installing(val version: String) : State
    }

    private const val TAG = "Segno"
    private const val SPACE_MARGIN = 20L * 1024 * 1024

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Android's own install question, waiting for Segno to be on screen to show it. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm

    /** Release builds update themselves; debug builds only link to the release. */
    val canInstall: Boolean get() = !BuildConfig.DEBUG

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun dir(context: Context) = File(context.cacheDir, "updates")

    /** Old update files, left from an install that finished or stopped. Blocking. */
    fun cleanUp(context: Context) {
        if (_state.value == State.Idle) dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Downloads [release]'s APK, or takes [local] (the CI test), checks it and installs it. */
    fun start(context: Context, release: Updates.Release, local: File? = null) {
        if (!canInstall || job?.isActive == true || _state.value != State.Idle) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Downloading(release.version, -1)
            val file = try {
                fetch(app, release, local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSpace) {
                fail(app.getString(R.string.upd_no_space))
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't download the update", e)
                fail(app.getString(R.string.upd_download_failed))
                return@launch
            }
            val verdict = runCatching { verify(app, file) }.getOrElse {
                Log.w(TAG, "Couldn't read the update", it)
                ApkCheck.Verdict.Unreadable
            }
            if (verdict != ApkCheck.Verdict.Ok) {
                Log.w(TAG, "Update refused: $verdict")
                file.delete()
                fail(explain(app, verdict))
                return@launch
            }
            _state.value = State.Installing(release.version)
            try {
                install(app, file)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the install", e)
                file.delete()
                fail(app.getString(R.string.upd_install_failed))
            }
        }
    }

    private class NoSpace : IOException()

    private suspend fun fetch(app: Context, release: Updates.Release, local: File?): File {
        val dir = dir(app).apply { mkdirs() }
        val out = File(dir, "Segno-${release.version}.apk")
        val part = File(dir, out.name + ".part")
        if (local != null) {
            local.inputStream().use { copy(it, part, local.length(), release.version, dir) }
        } else {
            val req = Request.Builder().url(release.apkUrl).header("User-Agent", "Segno-Android/${BuildConfig.VERSION_NAME}").build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body.byteStream().use { copy(it, part, r.body.contentLength(), release.version, dir) }
            }
        }
        out.delete()
        if (!part.renameTo(out)) throw IOException("Couldn't keep the update file")
        return out
    }

    private suspend fun copy(input: InputStream, to: File, total: Long, version: String, dir: File) {
        if (total > 0 && dir.usableSpace < total * 2 + SPACE_MARGIN) throw NoSpace()
        val buf = ByteArray(64 * 1024)
        var got = 0L
        var shown = -1
        to.outputStream().use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                got += n
                val pct = if (total > 0) (got * 100 / total).toInt() else -1
                if (pct != shown) {
                    shown = pct
                    _state.value = State.Downloading(version, pct)
                }
            }
        }
    }

    // Checking ----------------------------------------------------------------------------------

    private val signingFlags: Int
        @Suppress("DEPRECATION")
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun verify(app: Context, file: File): ApkCheck.Verdict {
        val pm = app.packageManager
        val mine = packageInfo(pm, app.packageName)?.let(::facts) ?: return ApkCheck.Verdict.Unreadable
        val apk = archiveInfo(pm, file.path)?.let(::facts)
        return ApkCheck.check(mine, apk)
    }

    private fun packageInfo(pm: PackageManager, name: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(signingFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(name, signingFlags)
        }
    }.getOrNull()

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signingFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, signingFlags)
        }

    @SuppressLint("PackageManagerGetSignatures")
    private fun facts(info: PackageInfo): ApkCheck.Facts {
        val signers: Set<String>
        val past: Set<String>
        if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo
            when {
                s == null -> {
                    signers = emptySet()
                    past = emptySet()
                }
                s.hasMultipleSigners() -> {
                    signers = s.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet()
                    past = emptySet()
                }
                else -> {
                    // Oldest first, the key it's signed with now last.
                    val history = s.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }
                    signers = history.takeLast(1).toSet()
                    past = history.toSet()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            signers = info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            past = emptySet()
        }
        return ApkCheck.Facts(info.packageName, PackageInfoCompat.getLongVersionCode(info), signers, past)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun explain(app: Context, v: ApkCheck.Verdict): String = when (v) {
        is ApkCheck.Verdict.WrongApp -> app.getString(R.string.upd_wrong_app)
        ApkCheck.Verdict.WrongSigner -> app.getString(R.string.upd_wrong_signer)
        is ApkCheck.Verdict.NotNewer -> app.getString(R.string.upd_not_newer)
        else -> app.getString(R.string.upd_unreadable)
    }

    // Installing --------------------------------------------------------------------------------

    private fun install(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+: no question when Segno installed the version it replaces.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("segno.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, InstallReceiver::class.java)
                    .setAction(InstallReceiver.ACTION)
                    .setPackage(app.packageName)
                    .putExtra(InstallReceiver.EXTRA_FILE, file.path)
                // Mutable: Android adds the result to it. It's explicit, so nobody else gets it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    // Results (from InstallReceiver) ------------------------------------------------------------

    internal fun needsConfirm(intent: Intent?) {
        if (intent == null) {
            finished(null)
            return
        }
        _confirm.value = intent
    }

    /** The install question was shown. */
    fun confirmShown() {
        _confirm.value = null
    }

    /** The install ended; [message]: what to tell, if anything. */
    internal fun finished(message: String?) {
        _state.value = State.Idle
        _confirm.value = null
        if (message != null) Messages.show(message)
    }

    private fun fail(message: String) = finished(message)
}
