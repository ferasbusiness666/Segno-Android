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
