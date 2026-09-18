package com.sublunar.amp.data

/**
 * What the connection and the data mode allow right now — the one answer the
 * library, the queue, playback, downloads and artwork all read.
 *
 * It used to be several answers held in several places: a metered flag here,
 * the mode there, and a "server reachable" latch that only a finished sync
 * could clear. Each part of the app followed whichever it happened to watch,
 * so after a network switch they disagreed for as long as the slowest of them
 * took — minutes, when that was the sync. One value, recomputed the moment any
 * of its inputs moves, is what makes them change together.
 *
 * This is the *courtesy* layer: it decides what to offer and what to attempt.
 * What may actually leave the phone is decided at the transport, by
 * NetworkGate, and that asks the system for itself every time.
 */
data class LinkRules(
    val connected: Boolean,
    val unmetered: Boolean,
    val mode: DataMode,
    val serverReachable: Boolean,
    /** The link is Wi-Fi — a network with neighbours on it, whatever it costs. */
    val wifi: Boolean = false,
) {
    /**
     * Speakers on the local network can be found and steered: on Wi-Fi, and —
     * in Wi-Fi Only — on Wi-Fi that isn't a metered hotspot. DLNA is a LAN
     * protocol; off Wi-Fi every piece of it is either pointless or a leak.
     */
    val mayUseLan: Boolean
        get() = connected && wifi && (mode != DataMode.WIFI_ONLY || unmetered)

    /** Sync, playlists, lyrics, scrobbles: anything that speaks to the server. */
    val mayTalkToServer: Boolean
        get() = connected && (mode != DataMode.WIFI_ONLY || unmetered)

    /** A track that is not on the phone can be played. */
    val mayStream: Boolean
        get() = mayTalkToServer && serverReachable

    /** Downloads and the library's cover art: free on an unmetered link, otherwise only Make it Hurt. */
    val mayMoveHeavyBytes: Boolean
        get() = connected && (unmetered || mode == DataMode.MAKE_IT_HURT)

    /**
     * The cover of the thing in front of the user — what is playing, the album
     * page that is open. Allowed wherever the music itself is: a sleeve is a
     * fraction of a percent of the album it belongs to, and a player that
     * streams a song over cellular but won't show its cover is saving nothing
     * anyone would notice. Library lists stay [mayMoveHeavyBytes]: a scroll
     * through uncached covers is real data, and the same picture arrives on
     * Wi-Fi soon enough.
     */
    val mayFetchFocusedArt: Boolean
        get() = mayTalkToServer

    /**
     * Why a track that needs the server can't play, in the words the screens
     * use; null when it can. Ordered by what the user can do about it: the
     * mode's own rule first, then the link, then the server.
     */
    val waitingFor: String?
        get() = when {
            mayStream -> null
            connected && mode == DataMode.WIFI_ONLY && !unmetered -> NetworkGate.WAITING_FOR_WIFI
            !connected && mode == DataMode.WIFI_ONLY -> NetworkGate.WAITING_FOR_WIFI
            !connected -> WAITING_FOR_CONNECTION
            else -> WAITING_FOR_SERVER
        }

    /** The same question for downloads and artwork. */
    val heavyWaitingFor: String?
        get() = when {
            mayMoveHeavyBytes -> null
            mode == DataMode.MAKE_IT_HURT -> WAITING_FOR_CONNECTION
            else -> NetworkGate.WAITING_FOR_WIFI
        }

    companion object {
        const val WAITING_FOR_CONNECTION = "Waiting for connection"
        const val WAITING_FOR_SERVER = "Waiting for server"

        /** Before anything is known: closed, the way every gate here starts. */
        val UNKNOWN = LinkRules(
            connected = false,
            unmetered = false,
            mode = DataMode.WIFI_ONLY,
            serverReachable = true,
        )
    }
}
