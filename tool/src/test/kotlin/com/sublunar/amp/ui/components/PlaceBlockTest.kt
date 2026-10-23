package com.sublunar.amp.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Moving shown rows within a playlist that holds rows the chosen library
 * doesn't show: those stay where they are, and none is lost.
 */
class PlaceBlockTest {

    /** A row: [key], and whether it is shown. */
    private data class Row(val key: String, val shown: Boolean = true)

    /** What the playlist page does with a drop: [indices] and [insertAt] are among the shown rows. */
    private fun drop(full: List<Row>, indices: Set<Int>, insertAt: Int): List<Row> {
        val shown = full.filter { it.shown }
        val moving = shown.filterIndexed { i, _ -> i in indices }.mapTo(HashSet()) { it.key }
        val remaining = shown.filterNot { it.key in moving }
        val before = remaining.getOrNull(insertAt.coerceIn(0, remaining.size))?.key
        return placeBlock(full, { it.key }, moving, before, remaining.last().key)
    }

    private fun keys(rows: List<Row>) = rows.joinToString("") { it.key }

    @Test
    fun `with every row shown, it is the plain block move`() {
        val full = "abcdef".map { Row(it.toString()) }
        val blocks = listOf(setOf(0), setOf(2), setOf(5), setOf(1, 2), setOf(0, 3), setOf(1, 4, 5))
        for (indices in blocks) {
            val remaining = full.filterIndexed { i, _ -> i !in indices }
            for (insertAt in 0..remaining.size) {
                val moving = full.filterIndexed { i, _ -> i in indices }
                val plain = remaining.toMutableList().apply { addAll(insertAt, moving) }
                assertEquals(keys(plain), keys(drop(full, indices, insertAt)), "$indices at $insertAt")
            }
        }
    }

    // a b [x] c [y] d — x and y are another library's.
    private val mixed = listOf(Row("a"), Row("b"), Row("x", false), Row("c"), Row("y", false), Row("d"))

    @Test
    fun `hidden rows keep their order and places`() {
        // d dragged above b.
        assertEquals("adbxcy", keys(drop(mixed, setOf(3), 1)))
        // a dragged to the bottom: after d, the last row shown.
        assertEquals("bxcyda", keys(drop(mixed, setOf(0), 3)))
        // d dragged to the top.
        assertEquals("dabxcy", keys(drop(mixed, setOf(3), 0)))
    }

    @Test
    fun `a block of ticked rows leaves the hidden rows between them behind`() {
        // b and d ticked, dropped above a.
        assertEquals("bdaxcy", keys(drop(mixed, setOf(1, 3), 0)))
    }

    @Test
    fun `a hidden row leading the playlist stays first`() {
        val full = listOf(Row("x", false), Row("a"), Row("b"))
        assertEquals("xba", keys(drop(full, setOf(1), 0)))
    }

    @Test
    fun `every row survives every move`() {
        for (i in 0..3) {
            for (insertAt in 0..3) {
                val moved = drop(mixed, setOf(i), insertAt)
                assertEquals(mixed.map { it.key }.sorted(), moved.map { it.key }.sorted())
                assertEquals("xy", keys(moved.filterNot { it.shown }))
            }
        }
    }
}
