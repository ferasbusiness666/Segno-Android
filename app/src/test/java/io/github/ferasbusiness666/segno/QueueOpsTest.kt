package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.core.Track
import io.github.ferasbusiness666.segno.playback.QueueOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QueueOpsTest {
    @Test
    fun shuffleKeepsTheCurrentSongFirst() {
        repeat(20) { seed ->
            val order = QueueOps.anchoredShuffle(10, 4, Random(seed))
            assertEquals(4, order[0])
            assertEquals((0 until 10).toSet(), order.toSet())
            assertEquals(10, order.size)
        }
        assertEquals(0, QueueOps.anchoredShuffle(0, 0).size)
        assertArrayEquals(intArrayOf(0), QueueOps.anchoredShuffle(1, 5))
    }

    @Test
    fun playNextGoesRightAfterTheCurrentSong() {
        // Shuffled order 3,0,2,1 playing 0; songs 4 and 5 were just added.
        val order = listOf(3, 0, 4, 2, 5, 1)
        assertArrayEquals(intArrayOf(3, 0, 4, 5, 2, 1), QueueOps.withNext(order, 0, listOf(4, 5)))
    }

    @Test
    fun addToQueueGoesLast() {
        val order = listOf(2, 4, 0, 1, 3)
        assertArrayEquals(intArrayOf(2, 0, 1, 3, 4), QueueOps.withLast(order, listOf(4)))
    }

    @Test
    fun move() {
        assertEquals(listOf("b", "c", "a", "d"), QueueOps.move(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), QueueOps.move(listOf("a", "b", "c", "d"), 3, 1))
        assertEquals(listOf("a", "b"), QueueOps.move(listOf("a", "b"), 0, 5))
    }

    @Test
    fun relatedSongsAreNewAndSongLength() {
        val related = listOf(
            Track("yt:aaaaaaaaaaa", "In queue", durationMs = 200_000),
            Track("yt:bbbbbbbbbbb", "Fine", durationMs = 180_000),
            Track("yt:ccccccccccc", "Two-hour mix", durationMs = 7_200_000),
            Track("yt:ddddddddddd", "Short", durationMs = 20_000),
            Track("yt:bbbbbbbbbbb", "Fine again", durationMs = 180_000),
            Track("yt:eeeeeeeeeee", "Unknown length"),
        )
        val picked = QueueOps.pickRelated(related, setOf("yt:aaaaaaaaaaa"))
        assertEquals(listOf("yt:bbbbbbbbbbb", "yt:eeeeeeeeeee"), picked.map { it.id })
    }
}
