package com.sublunar.amp.data

import com.sublunar.amp.data.PendingAction.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Sending the offline queue: in order, stopping at the first write nobody
 * answered, dropping — and noting — the ones the server refused, and leaving
 * another source's entries alone.
 */
class PendingReplayTest {

    private fun action(kind: Kind, id: String, source: String? = "a") =
        PendingAction(kind, id, atMs = 0L, sourceId = source)

    private val everyone = { _: PendingAction -> true }

    @Test
    fun `everything saved leaves nothing`() = runBlocking {
        val queue = listOf(action(Kind.STAR_SONG, "1"), action(Kind.SCROBBLE, "2"))
        assertEquals(Replay(emptyList(), emptyList()), replay(queue, everyone) { WriteResult.Saved })
    }

    @Test
    fun `the first unanswered write stops the run, and it and the rest stay in order unsent`() = runBlocking {
        val queue = listOf(
            action(Kind.SCROBBLE, "1"),
            action(Kind.SCROBBLE, "2"),
            action(Kind.STAR_SONG, "3"),
            action(Kind.SCROBBLE, "4"),
        )
        val sent = mutableListOf<String>()
        val run = replay(queue, everyone) {
            sent += it.id
            if (it.id == "2") WriteResult.Unreachable(UNREACHABLE) else WriteResult.Saved
        }
        assertEquals(listOf("1", "2"), sent)
        assertEquals(queue.drop(1), run.left)
        assertTrue(run.refused.isEmpty())
    }

    @Test
    fun `a refusal is dropped and noted, and the run goes on past it`() = runBlocking {
        val queue = listOf(
            action(Kind.STAR_SONG, "gone"),
            action(Kind.SCROBBLE, "1"),
            action(Kind.RATE_SONG, "shared"),
            action(Kind.SCROBBLE, "2"),
        )
        val sent = mutableListOf<String>()
        val run = replay(queue, everyone) {
            sent += it.id
            when (it.id) {
                "gone" -> WriteResult.Refused("The server doesn't have that")
                "shared" -> WriteResult.Refused("The server doesn't allow that")
                else -> WriteResult.Saved
            }
        }
        assertEquals(listOf("gone", "1", "shared", "2"), sent)
        assertEquals(emptyList(), run.left)
        assertEquals(listOf("The server doesn't have that", "The server doesn't allow that"), run.refused)
    }

    @Test
    fun `star, unstar, star with the unstar unanswered keeps the last two in order`() = runBlocking {
        val star = action(Kind.STAR_SONG, "x")
        val unstar = action(Kind.UNSTAR_SONG, "x")
        val again = action(Kind.STAR_SONG, "x")
        val run = replay(listOf(star, unstar, again), everyone) {
            if (it === unstar) WriteResult.Unreachable(UNREACHABLE) else WriteResult.Saved
        }
        assertEquals(listOf(unstar, again), run.left)
    }

    @Test
    fun `another source's entries stay where they are, unsent, and one with no source is sent`() = runBlocking {
        val mine = action(Kind.STAR_SONG, "1", source = "a")
        val theirs = action(Kind.STAR_SONG, "2", source = "b")
        val legacy = action(Kind.SCROBBLE, "3", source = null)
        val alsoTheirs = action(Kind.SCROBBLE, "4", source = "b")
        val sent = mutableListOf<String>()
        val run = replay(listOf(mine, theirs, legacy, alsoTheirs), { it.sourceId == null || it.sourceId == "a" }) {
            sent += it.id
            WriteResult.Saved
        }
        assertEquals(listOf("1", "3"), sent)
        assertEquals(listOf(theirs, alsoTheirs), run.left)
    }

    @Test
    fun `an unanswered write keeps another source's entries in their places`() = runBlocking {
        val theirs = action(Kind.SCROBBLE, "1", source = "b")
        val stuck = action(Kind.SCROBBLE, "2", source = "a")
        val after = action(Kind.SCROBBLE, "3", source = "b")
        val run = replay(listOf(theirs, stuck, after), { it.sourceId == "a" }) { WriteResult.Unreachable(UNREACHABLE) }
        assertEquals(listOf(theirs, stuck, after), run.left)
    }

    @Test
    fun `a like replaces what was queued for the same thing, and nothing else`() {
        val queue = listOf(
            action(Kind.UNSTAR_SONG, "a"),
            action(Kind.UNSTAR_SONG, "b"),
            action(Kind.STAR_ALBUM, "a"),
            action(Kind.SCROBBLE, "a"),
            action(Kind.RATE_ALBUM, "a"),
        )
        assertEquals(queue.drop(1), queue.without(action(Kind.STAR_SONG, "a")))
        assertEquals(queue, queue.without(action(Kind.RATE_SONG, "a")))
    }

    @Test
    fun `a play never replaces another play`() {
        val queue = listOf(action(Kind.SCROBBLE, "a"), action(Kind.SCROBBLE, "a"))
        assertEquals(queue, queue.without(action(Kind.SCROBBLE, "a")))
    }

    @Test
    fun `the same thing on another source is a different thing, but an entry with no source is anyone's`() {
        val other = action(Kind.UNSTAR_SONG, "a", source = "b")
        val legacy = action(Kind.UNSTAR_SONG, "a", source = null)
        assertEquals(listOf(other), listOf(other, legacy).without(action(Kind.STAR_SONG, "a", source = "a")))
    }
}
