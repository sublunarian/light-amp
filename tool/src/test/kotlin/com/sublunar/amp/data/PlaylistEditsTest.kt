package com.sublunar.amp.data

import com.sublunar.amp.data.PlaylistEdit.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Playlist edits kept offline: how they fold, show, and replay — see [PlaylistEdits]. */
class PlaylistEditsTest {

    private val phoneId = PHONE_MADE_PREFIX + "x"

    @Test
    fun `a playlist made offline stays one create, whatever is done to it`() {
        var queue = queued(emptyList(), PlaylistEdit(Kind.CREATE, phoneId, "Mix", listOf("a")))
        queue = queued(queue, PlaylistEdit(Kind.ADD, phoneId, trackIds = listOf("b")))
        queue = queued(queue, PlaylistEdit(Kind.RENAME, phoneId, name = "Road"))
        queue = queued(queue, PlaylistEdit(Kind.SET, phoneId, trackIds = listOf("b", "a")))
        assertEquals(listOf(PlaylistEdit(Kind.CREATE, phoneId, "Road", listOf("b", "a"))), queue)
    }

    @Test
    fun `deleting a playlist made offline leaves nothing to send`() {
        val queue = queued(emptyList(), PlaylistEdit(Kind.CREATE, phoneId, "Mix", listOf("a")))
        assertEquals(emptyList(), queued(queue, PlaylistEdit(Kind.DELETE, phoneId)))
    }

    @Test
    fun `songs added one by one go as one add`() {
        var queue = queued(emptyList(), PlaylistEdit(Kind.ADD, "p", trackIds = listOf("a")))
        queue = queued(queue, PlaylistEdit(Kind.ADD, "p", trackIds = listOf("b")))
        assertEquals(listOf(PlaylistEdit(Kind.ADD, "p", trackIds = listOf("a", "b"))), queue)
    }

    @Test
    fun `a whole list replaces the adds before it, and not other playlists' edits`() {
        var queue = queued(emptyList(), PlaylistEdit(Kind.ADD, "p", trackIds = listOf("a")))
        queue = queued(queue, PlaylistEdit(Kind.ADD, "q", trackIds = listOf("z")))
        queue = queued(queue, PlaylistEdit(Kind.SET, "p", trackIds = listOf("b")))
        assertEquals(
            listOf(PlaylistEdit(Kind.ADD, "q", trackIds = listOf("z")), PlaylistEdit(Kind.SET, "p", trackIds = listOf("b"))),
            queue,
        )
    }

    @Test
    fun `the list shows what is waiting`() {
        val server = listOf(
            Playlist("p", "Old", null, 0, 0, emptyList()),
            Playlist("q", "Gone", null, 0, 0, emptyList()),
        )
        val queue = listOf(
            PlaylistEdit(Kind.RENAME, "p", name = "New"),
            PlaylistEdit(Kind.DELETE, "q"),
            PlaylistEdit(Kind.CREATE, phoneId, "Mix", listOf("a")),
        )
        val shown = withEdits(server, queue, nowMs = 5)
        assertEquals(listOf("New", "Mix"), shown.map { it.name })
        assertEquals(listOf("a"), shown.last().trackIds)
    }

    @Test
    fun `replacing a list removes extra copies from the end and appends what's missing`() {
        val plan = replacement(server = listOf("a", "b", "a", "c"), target = listOf("a", "c", "d"))
        assertEquals(mapOf(1 to "b", 2 to "a"), plan.removeAt)
        assertEquals(listOf("d"), plan.append)
        assertFalse(plan.reorder)
    }

    @Test
    fun `a reorder is asked for only when the order still differs`() {
        assertTrue(replacement(listOf("a", "b"), listOf("b", "a")).reorder)
        val same = replacement(listOf("a", "b"), listOf("a", "b"))
        assertEquals(Replacement(emptyMap(), emptyList(), false), same)
    }
}
