package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.update.ApkCheck
import io.github.ferasbusiness666.segno.update.ApkCheck.Facts
import io.github.ferasbusiness666.segno.update.ApkCheck.Verdict
import io.github.ferasbusiness666.segno.update.Updates
import io.github.ferasbusiness666.segno.update.Updates.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateParseTest {
    @Test
    fun findsTheApkAndNotes() {
        val json = """{"tag_name":"v1.2.0","html_url":"https://github.com/x/releases/tag/v1.2.0","draft":false,"prerelease":false,
            "body":"### New\n- **Faster** search.\n- Delete from phone.\n\n### Fixed\n- A crash.\n- Another one.\n- And a third.",
            "assets":[{"name":"notes.txt","browser_download_url":"https://github.com/x/notes.txt"},
                      {"name":"Segno-1.2.0.apk","browser_download_url":"https://github.com/x/releases/download/v1.2.0/Segno-1.2.0.apk"}]}"""
        val r = Updates.parse(json)!!
        assertEquals("1.2.0", r.version)
        assertEquals("https://github.com/x/releases/download/v1.2.0/Segno-1.2.0.apk", r.apkUrl)
        assertEquals("Segno-1.2.0.apk", r.apkName)
        assertEquals("https://github.com/x/releases/tag/v1.2.0", r.pageUrl)
        assertEquals("• Faster search.\n• Delete from phone.\n• A crash.\n• Another one.\n…", r.notes)
    }

    @Test
    fun noApkMeansTheReleasePage() {
        val r = Updates.parse("""{"tag_name":"1.3.0","html_url":"https://github.com/x/releases/tag/1.3.0","assets":[]}""")!!
        assertEquals("", r.apkUrl)
        assertEquals("https://github.com/x/releases/tag/1.3.0", r.pageUrl)
        assertEquals("", r.notes)
    }

    @Test
    fun preReleasesAndJunkAreIgnored() {
        assertNull(Updates.parse("""{"tag_name":"2.0.0","prerelease":true,"assets":[]}"""))
        assertNull(Updates.parse("not json"))
    }
}

class PickApkTest {
    private fun a(name: String, url: String = "https://github.com/x/releases/download/v/$name") = Asset(name, url)

    @Test
    fun prefersTheExactName() {
        val list = listOf(a("Segno-1.0.0.apk"), a("Segno-1.0.1.apk"), a("Segno-1.0.1-debug.apk"))
        assertEquals("Segno-1.0.1.apk", Updates.pickApk(list, "1.0.1")!!.name)
    }

    @Test
    fun otherSegnoApkWhenTheNameDiffers() {
        assertEquals("segno-1.0.1-release.apk", Updates.pickApk(listOf(a("segno-1.0.1-release.apk")), "1.0.1")!!.name)
    }

    @Test
    fun skipsDebugOtherAppsAndForeignLinks() {
        assertNull(Updates.pickApk(listOf(a("Segno-1.0.1-debug.apk")), "1.0.1"))
        assertNull(Updates.pickApk(listOf(a("Other-1.0.1.apk")), "1.0.1"))
        assertNull(Updates.pickApk(listOf(a("Segno-1.0.1.apk", "http://github.com/x/Segno-1.0.1.apk")), "1.0.1"))
        assertNull(Updates.pickApk(listOf(a("Segno-1.0.1.apk", "https://example.com/Segno-1.0.1.apk")), "1.0.1"))
        assertNull(Updates.pickApk(listOf(a("Segno-1.0.1.zip")), "1.0.1"))
    }
}

class ApkCheckTest {
    private val pkg = "io.github.ferasbusiness666.segno"
    private val mine = Facts(pkg, 1_000_000, setOf("aa"))

    @Test
    fun sameAppSameKeyNewerIsOk() {
        assertEquals(Verdict.Ok, ApkCheck.check(mine, Facts(pkg, 1_000_001, setOf("aa"))))
    }

    @Test
    fun otherAppIsRefused() {
        assertEquals(Verdict.WrongApp("com.evil"), ApkCheck.check(mine, Facts("com.evil", 9_000_000, setOf("aa"))))
        assertEquals(Verdict.WrongApp("$pkg.debug"), ApkCheck.check(mine, Facts("$pkg.debug", 9_000_000, setOf("aa"))))
    }

    @Test
    fun otherKeyIsRefused() {
        assertEquals(Verdict.WrongSigner, ApkCheck.check(mine, Facts(pkg, 1_000_001, setOf("bb"))))
        assertEquals(Verdict.WrongSigner, ApkCheck.check(mine, Facts(pkg, 1_000_001, emptySet())))
        // Signed with another key next to ours: ours is there, so it passes (Android checks the rest).
        assertEquals(Verdict.Ok, ApkCheck.check(mine, Facts(pkg, 1_000_001, setOf("aa", "bb"))))
    }

    @Test
    fun rotatedKeyThatVouchesForOursIsOk() {
        assertEquals(Verdict.Ok, ApkCheck.check(mine, Facts(pkg, 1_000_001, setOf("cc"), pastSigners = setOf("aa", "cc"))))
        assertEquals(Verdict.WrongSigner, ApkCheck.check(mine, Facts(pkg, 1_000_001, setOf("cc"), pastSigners = setOf("dd", "cc"))))
    }

    @Test
    fun sameOrOlderIsRefused() {
        assertEquals(Verdict.NotNewer(1_000_000, 1_000_000), ApkCheck.check(mine, Facts(pkg, 1_000_000, setOf("aa"))))
        assertEquals(Verdict.NotNewer(1_000_000, 999_999), ApkCheck.check(mine, Facts(pkg, 999_999, setOf("aa"))))
    }

    @Test
    fun unreadableFile() {
        assertEquals(Verdict.Unreadable, ApkCheck.check(mine, null))
        assertEquals(Verdict.Unreadable, ApkCheck.check(mine, Facts(null, 0, emptySet())))
    }
}
