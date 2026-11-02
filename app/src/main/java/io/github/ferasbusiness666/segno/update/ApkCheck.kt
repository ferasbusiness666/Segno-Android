package io.github.ferasbusiness666.segno.update

/**
 * The checks a downloaded APK must pass before Segno installs it, kept apart from Android so
 * they can be tested. Android checks the signature again when it installs; these run first so a
 * wrong file gets a clear message instead of a failed install.
 */
object ApkCheck {
    /** What Segno knows about an app: its package, versionCode and signing certificates (SHA-256, hex). */
    data class Facts(
        val packageName: String?,
        val versionCode: Long,
        /** The certificates it's signed with now. */
        val signers: Set<String>,
        /** Older certificates it proves it may replace (key rotation, Android 9+). */
        val pastSigners: Set<String> = emptySet(),
    )

    sealed interface Verdict {
        data object Ok : Verdict
        data object Unreadable : Verdict
        data class WrongApp(val packageName: String?) : Verdict
        data object WrongSigner : Verdict
        data class NotNewer(val installed: Long, val offered: Long) : Verdict
    }

    fun check(installed: Facts, apk: Facts?): Verdict {
        if (apk == null || apk.packageName == null) return Verdict.Unreadable
        if (apk.packageName != installed.packageName) return Verdict.WrongApp(apk.packageName)
        if (apk.signers.isEmpty() || installed.signers.isEmpty()) return Verdict.WrongSigner
        // The new APK must be signed by the same key, or show it took over from that key.
        val vouched = apk.signers + apk.pastSigners
        if (!installed.signers.all { it in vouched }) return Verdict.WrongSigner
        if (apk.versionCode <= installed.versionCode) return Verdict.NotNewer(installed.versionCode, apk.versionCode)
        return Verdict.Ok
    }
}
