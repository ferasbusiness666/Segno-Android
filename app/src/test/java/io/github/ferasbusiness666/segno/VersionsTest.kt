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
    fun versionCodeGoesUpWithTheName() {
        assertEquals(1_000_000L, Versions.code("1.0.0"))
        assertEquals(1_000_001L, Versions.code("v1.0.1"))
        assertEquals(1_002_003L, Versions.code("1.2.3"))
        assertEquals(2_000_000L, Versions.code("2"))
        assertNull(Versions.code("1.2.3.4"))
        assertNull(Versions.code("1.1000.0"))
        assertNull(Versions.code("beta"))
        // Every newer name has a bigger code.
        val names = listOf("0.9.9", "1.0.0", "1.0.1", "1.0.10", "1.1.0", "1.9.999", "2.0.0", "10.0.0")
        for ((a, b) in names.zipWithNext()) {
            assertTrue("$b after $a", Versions.isNewer(b, a))
            assertTrue("code of $b after $a", Versions.code(b)!! > Versions.code(a)!!)
        }
    }

    @Test
    fun parts() {
        assertEquals(listOf(1, 2, 3), Versions.parts("v1.2.3-beta+5"))
        assertNull(Versions.parts("abc"))
    }
}
