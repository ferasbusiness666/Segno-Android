package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.core.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun newer() {
        assertTrue(Versions.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("1.1", "1.0.9"))
        assertTrue(Versions.isNewer("2.0.0", "1.12.3"))
        assertTrue(Versions.isNewer("1.0.10", "1.0.9"))
        assertTrue(Versions.isNewer("1.0.0.1", "1.0.0"))
    }

    @Test
    fun notNewer() {
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        assertFalse(Versions.isNewer("v1.0", "1.0.0"))
        assertFalse(Versions.isNewer("0.9.9", "1.0.0"))
        assertFalse(Versions.isNewer("1.1.0-beta1", "1.0.0"))
        assertFalse(Versions.isNewer("latest", "1.0.0"))
        assertFalse(Versions.isNewer("", "1.0.0"))
    }

    @Test
    fun parts() {
        assertEquals(listOf(1, 2, 3), Versions.parts("v1.2.3-beta+5"))
        assertNull(Versions.parts("abc"))
    }
}

class UpdateParseTest {
    @Test
    fun findsTheApk() {
        val json = """{"tag_name":"v1.2.0","html_url":"https://github.com/x/releases/tag/v1.2.0","draft":false,"prerelease":false,
            "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},{"name":"Segno-1.2.0.apk","browser_download_url":"https://x/Segno-1.2.0.apk"}]}"""
        val r = io.github.ferasbusiness666.segno.update.Updates.parse(json)!!
        assertEquals("1.2.0", r.version)
        assertEquals("https://x/Segno-1.2.0.apk", r.url)
    }

    @Test
    fun noApkMeansTheReleasePage() {
        val json = """{"tag_name":"1.3.0","html_url":"https://github.com/x/releases/tag/1.3.0","assets":[]}"""
        assertEquals("https://github.com/x/releases/tag/1.3.0", io.github.ferasbusiness666.segno.update.Updates.parse(json)!!.url)
    }

    @Test
    fun preReleasesAndJunkAreIgnored() {
        assertNull(io.github.ferasbusiness666.segno.update.Updates.parse("""{"tag_name":"2.0.0","prerelease":true,"assets":[]}"""))
        assertNull(io.github.ferasbusiness666.segno.update.Updates.parse("not json"))
    }
}
