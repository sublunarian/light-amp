package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A reorder sends only the moves it needs, and they land the order asked for. */
class PlaylistMovesTest {

    /** Each move as a server makes it: out, then in right after its key, or first. */
    private fun <K> apply(current: List<K>, moves: List<Pair<K, K?>>): List<K> {
        val list = current.toMutableList()
        for ((key, after) in moves) {
            list.remove(key)
            list.add(if (after == null) 0 else list.indexOf(after) + 1, key)
        }
        return list
    }

    private val songs = (1..500).map { "s$it" }

    @Test
    fun `one song moved anywhere is one move`() {
        val toTop = listOf(songs.last()) + songs.dropLast(1)
        val toBottom = songs.drop(1) + songs.first()
        val toMiddle = songs.toMutableList().apply { add(250, removeAt(10)) }
        for (wanted in listOf(toTop, toBottom, toMiddle)) {
            val moves = placementMoves(songs, wanted)
            assertEquals(1, moves.size)
            assertEquals(wanted, apply(songs, moves))
        }
    }

    @Test
    fun `a block of songs moved together is one move each`() {
        val block = songs.subList(100, 105)
        val wanted = songs.filterNot { it in block }.toMutableList().apply { addAll(400, block) }
        val moves = placementMoves(songs, wanted)
        assertEquals(5, moves.size)
        assertEquals(wanted, apply(songs, moves))
    }

    @Test
    fun `nothing moves when nothing changed`() {
        assertEquals(emptyList(), placementMoves(songs, songs))
    }

    @Test
    fun `every order of a short list lands`() {
        val keys = listOf("a", "b", "c", "d", "e")
        fun permutations(rest: List<String>): List<List<String>> =
            if (rest.isEmpty()) listOf(emptyList()) else rest.flatMap { k -> permutations(rest - k).map { listOf(k) + it } }
        for (wanted in permutations(keys)) {
            val moves = placementMoves(keys, wanted)
            assertEquals(wanted, apply(keys, moves), "$wanted")
            if (wanted.first() == keys.first()) assertTrue(moves.none { it.second == null }, "$wanted")
        }
    }
}
