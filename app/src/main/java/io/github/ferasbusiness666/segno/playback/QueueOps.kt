package io.github.ferasbusiness666.segno.playback

import io.github.ferasbusiness666.segno.core.Track
import kotlin.random.Random

/**
 * Queue arithmetic, kept apart from the player so it can be tested.
 *
 * The queue is the player's playlist. With shuffle on, the player follows a separate play order
 * (a list of playlist positions); these helpers keep that order sensible: the song playing stays
 * first when shuffle is turned on, "Play next" really plays next, and "Add to queue" goes last.
 */
object QueueOps {
    /** A shuffled play order that starts with [current] and visits every other song once. */
    fun anchoredShuffle(count: Int, current: Int, random: Random = Random.Default): IntArray {
        if (count <= 0) return IntArray(0)
        val start = current.coerceIn(0, count - 1)
        val rest = (0 until count).filter { it != start }.shuffled(random)
        return (listOf(start) + rest).toIntArray()
    }

    /** [order] with [inserted] moved to right after [current]. */
    fun withNext(order: List<Int>, current: Int, inserted: List<Int>): IntArray {
        val out = order.filter { it !in inserted }.toMutableList()
        val at = out.indexOf(current)
        out.addAll(if (at < 0) 0 else at + 1, inserted)
        return out.toIntArray()
    }

    /** [order] with [inserted] moved to the end. */
    fun withLast(order: List<Int>, inserted: List<Int>): IntArray =
        (order.filter { it !in inserted } + inserted).toIntArray()

    /** A copy of [list] with the item at [from] moved to [to]. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = list.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        return out
    }

    /** Songs to carry on with when the queue ends: new ones only, and not hour-long mixes. */
    fun pickRelated(related: List<Track>, inQueue: Set<String>, max: Int = 10): List<Track> =
        related.asSequence()
            .filter { it.id !in inQueue }
            .filter { it.durationMs == 0L || it.durationMs in 60_000L..12 * 60_000L }
            .distinctBy { it.id }
            .take(max)
            .toList()
}
