package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.playback.Sleep
import io.github.ferasbusiness666.segno.playback.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {
    @Test
    fun choices() {
        assertEquals(listOf(15, 30, 45, 60), SleepTimer.MINUTES)
        assertEquals(Sleep.At(1_000 + 15 * 60_000L), SleepTimer.after(1_000, 15))
    }

    @Test
    fun plusTenMinutes() {
        assertEquals(Sleep.At(5_000 + 600_000L), SleepTimer.extend(Sleep.At(5_000), 1_000))
        // Already past: ten minutes from now.
        assertEquals(Sleep.At(9_000 + 600_000L), SleepTimer.extend(Sleep.At(5_000), 9_000))
    }

    @Test
    fun remaining() {
        assertNull(SleepTimer.remainingMs(Sleep.Off, 0, 10_000))
        assertEquals(60_000L, SleepTimer.remainingMs(Sleep.At(100_000), 40_000, null))
        assertEquals(12_000L, SleepTimer.remainingMs(Sleep.EndOfSong, 0, 12_000))
        assertNull(SleepTimer.remainingMs(Sleep.EndOfSong, 0, null))
    }

    @Test
    fun fadesOutOverTheLast30Seconds() {
        assertEquals(1f, SleepTimer.volumeFor(null), 0f)
        assertEquals(1f, SleepTimer.volumeFor(60_000), 0f)
        assertEquals(1f, SleepTimer.volumeFor(30_000), 0f)
        assertEquals(0.5f, SleepTimer.volumeFor(15_000), 0.001f)
        assertEquals(0.1f, SleepTimer.volumeFor(3_000), 0.001f)
        assertEquals(0f, SleepTimer.volumeFor(0), 0f)
        assertEquals(0f, SleepTimer.volumeFor(-500), 0f)
    }

    @Test
    fun timeLeftReadsWell() {
        assertEquals("23:41", SleepTimer.clock(23 * 60 + 41))
        assertEquals("1:05:00", SleepTimer.clock(65 * 60))
        assertEquals("0:09", SleepTimer.clock(9))
        assertEquals("24m", SleepTimer.badge(23 * 60 + 1))
        assertEquals("1m", SleepTimer.badge(60))
        assertEquals("45s", SleepTimer.badge(45))
        assertEquals(2L, SleepTimer.secsLeft(10_001, 8_500))
        assertEquals(0L, SleepTimer.secsLeft(1_000, 5_000))
    }
}
