package com.sublunar.amp.source.plex.companion

import com.sublunar.amp.source.plex.PlexClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Players are spoken to in clear, and on a server the user owns the client's
 * token opens the whole Plex account, so which token goes where is pinned here.
 */
class PlexCompanionTokenTest {

    private val client = PlexClient("http://plex.example:32400", "account-token", clientIdentifier = "amp-1234")

    private val player = PlexPlayer(id = "player-1", name = "Living Room", directUrl = "http://192.0.2.5:32500")

    @Test
    fun `the server gets the client's token, a player only the key it was handed`() {
        assertEquals("account-token", client.companionToken(target = null, key = "transient-1"))
        assertEquals("transient-1", client.companionToken(target = player, key = "transient-1"))
        assertNull(client.companionToken(target = player, key = null))
    }

    @Test
    fun `only a transient token from the server is taken as a player's key`() {
        val minted = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MediaContainer size="0" token="transient-90904684-f91a-4391-8bf7-e0dfa7240285"/>
        """.trimIndent()
        assertEquals("transient-90904684-f91a-4391-8bf7-e0dfa7240285", transientKeyIn(minted))
        // A lasting token is never handed on, whatever the server answers.
        assertNull(transientKeyIn("""<MediaContainer size="0" token="account-token"/>"""))
        assertNull(transientKeyIn("""<MediaContainer size="0"/>"""))
        assertNull(transientKeyIn(""))
    }
}
