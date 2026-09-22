package com.sublunar.amp.playback

import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlin.test.assertFalse
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
}
