package com.sublunar.amp.data

import kotlinx.serialization.Serializable

/**
 * What one server turned out to implement, learned by asking it.
 *
 * Subsonic has no way to ask a server what it supports. There is no capability
 * call, no "not implemented" error, and the version a server reports is a claim
 * rather than a promise — Bandcamp's says 1.16.1 while lacking calls that have
 * been in the API since 1.8.0. The clients that deal with this (Amperfy's
 * podcast support, Feishin's jukebox) all do the same thing: make the call once,
 * treat a failure as "not supported", and remember the answer per server.
 *
 * **The shape of the answer is the evidence, not its content.** Any Subsonic
 * reply, an error included, proves the server has that endpoint; something that
 * isn't Subsonic at all, or a 404, means it hasn't. That lets every probe be
 * harmless: none of them has to succeed, so none has to ask for anything real.
 *
 * Nothing here names a server. A missing endpoint is a fact about one address,
 * found by asking, and it holds for Bandcamp, gonic, Navidrome and whatever
 * comes next.
 */
@Serializable
data class ServerFeatures(
    /**
     * What the server called itself when this was learned — its API version,
     * and its own name and version where it gives them. A server that is
     * upgraded says something different, and everything here is asked again.
     */
    val checkedVersion: String = "",
    /** Endpoints this server answered for as "no such endpoint". */
    val missing: List<String> = emptyList(),
    /**
     * Which set of probes produced this. A build that asks about something new
     * doesn't know the answer for a server already on file, so a bump here has
     * every server asked again — the same trick as the track parser's
     * generation. Zero is what a record written before this field reads as,
     * which is exactly what it deserves.
     */
    val generation: Int = 0,
) {
    fun has(endpoint: String): Boolean = endpoint !in missing

    companion object {
        /**
         * Bumped whenever a probe is added or changed, so the answers on file
         * are asked again rather than being trusted for a question that wasn't
         * put. 1: ratings, radio, original download. 2: the scan call too.
         */
        const val GENERATION = 2

        /** Ratings: `setRating`. */
        const val SET_RATING = "setRating"

        /** A radio seeded by one song: `getSimilarSongs`. */
        const val SIMILAR_SONGS = "getSimilarSongs"

        /** Keeping a copy at original quality: `download`. */
        const val DOWNLOAD = "download"

        /**
         * Asking the server to look for new files: `startScan`, stood in for by
         * the read-only `getScanStatus` beside it. A server with neither is not
         * refusing the scan, it simply has no such idea — and saying it refused
         * would be telling the user their library is stale when it isn't.
         */
        const val SCAN_STATUS = "getScanStatus"
    }
}
