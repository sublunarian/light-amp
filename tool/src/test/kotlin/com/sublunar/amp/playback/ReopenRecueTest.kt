package com.sublunar.amp.playback

import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ReopenRecueTest {

    /**
     * The one case the re-cue is for: the app was left paused, the service
     * stopped itself after its idle limit, and the process lived on to be
     * reopened. The player comes back empty under the queue that still shows.
     */
    @Test
    fun `a service that stopped itself gets the queue back`() {
        assertTrue(
            needsRecue(
                carriedOver = true,
                playerIndex = NO_MEDIA_ITEM,
                playerPlaying = false,
                casting = false,
                parked = false,
            ),
        )
    }

    @Test
    fun `a cold start has nothing to put back`() {
        assertFalse(
            needsRecue(
                carriedOver = false,
                playerIndex = NO_MEDIA_ITEM,
                playerPlaying = false,
                casting = false,
                parked = false,
            ),
        )
    }

    /** The ordinary reopen: the service kept its queue, and it is where the truth is. */
    @Test
    fun `a service that kept its queue is left alone`() {
        assertFalse(needsRecue(true, playerIndex = 3, playerPlaying = false, casting = false, parked = false))
        assertFalse(needsRecue(true, playerIndex = 3, playerPlaying = true, casting = false, parked = false))
    }

    /** Whatever the index says, a service making sound has a queue; cueing over it would stop it. */
    @Test
    fun `a playing service is never cued over`() {
        assertFalse(needsRecue(true, playerIndex = NO_MEDIA_ITEM, playerPlaying = true, casting = false, parked = false))
    }

    @Test
    fun `a cast or a park prepares the player its own way`() {
        assertFalse(needsRecue(true, playerIndex = NO_MEDIA_ITEM, playerPlaying = false, casting = true, parked = false))
        assertFalse(needsRecue(true, playerIndex = NO_MEDIA_ITEM, playerPlaying = false, casting = false, parked = true))
    }

    /** The screen carried track 2; the service is on what each case says, 1:40 in. */
    private fun landing(
        playerIndex: Int,
        pickedIndex: Int = -1,
        recued: Boolean = false,
        casting: Boolean = false,
        parked: Boolean = false,
    ) = connectLanding(
        playerIndex = playerIndex,
        playerPositionMs = 100_000L,
        shownIndex = 2,
        pickedIndex = pickedIndex,
        recued = recued,
        casting = casting,
        parked = parked,
    )

    /**
     * Backed out on track 2; the service played on to track 5 and paused. A
     * paused player never says its position again, so the reopen has to land
     * where the service is, or it shows 0:00 for good and saves that.
     */
    @Test
    fun `a service that moved on is landed on at its own position`() {
        assertEquals(100_000L, landing(playerIndex = 5))
    }

    @Test
    fun `the same track, an empty player or a re-cue has nothing to land on`() {
        assertNull(landing(playerIndex = 2))
        assertNull(landing(playerIndex = NO_MEDIA_ITEM))
        assertNull(landing(playerIndex = 5, recued = true))
    }

    @Test
    fun `a cast says where playback is itself`() {
        assertNull(landing(playerIndex = 5, casting = true))
    }

    /**
     * Waiting for Wi-Fi on track 2's stream, the user stepped on: the screen
     * moved, the service stayed on the track that failed. Landing there would
     * undo the step, and resuming would cue the wrong track.
     */
    @Test
    fun `a park keeps the marker the user moved`() {
        assertNull(landing(playerIndex = 5, parked = true))
    }

    /** Next pressed while the handle connected: the skip reached the service after its snapshot. */
    @Test
    fun `a track a press went to while connecting starts from the top`() {
        assertEquals(0L, landing(playerIndex = 3, pickedIndex = 3))
    }
}
