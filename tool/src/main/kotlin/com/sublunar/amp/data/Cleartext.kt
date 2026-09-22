package com.sublunar.amp.data

import android.security.NetworkSecurityPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether this build may speak plain HTTP to a server, and what to say when it
 * may not.
 *
 * Side-loaded builds carry usesCleartextTraffic in their manifest overlays,
 * because a self-hosted server on a LAN is normally plain http://. A build made
 * by Light's store builder gets only the generated manifest, and there the
 * platform refuses every cleartext request before it is sent. The refusal comes
 * back as an exception written for a developer — "CLEARTEXT communication to
 * 192.168.1.10 not permitted by network security policy" — or, on a screen that
 * only reports that sign-in failed, as nothing at all. So the places a person
 * gives an address ask first, and say it in words.
 *
 * The platform is asked per host, as OkHttp asks it, so the answer is the one
 * the request itself would get. In a side-loaded build it is always yes, and
 * none of this is ever said.
 */
object Cleartext {

    /** Short enough for the one row a sync failure is shown in. */
    const val REFUSED_BRIEF = "This build can only reach https:// servers"

    /** The whole of it, for a form with room to explain. */
    const val REFUSED = "$REFUSED_BRIEF. A plain http:// server needs the side-loaded build, " +
        "until Light lets a tool opt in."

    /** Whether [address] is plain http:// to a host this build may not speak cleartext to. */
    fun refuses(address: String): Boolean =
        refuses(address) { host -> NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) }

    /**
     * [refuses] with the platform's answer passed in, so it can be checked off
     * the phone. Anything that isn't a whole http:// or https:// address is not
     * refused here: an address without a scheme is tried as https:// first (see
     * SubsonicConfig.candidates), and one that doesn't parse fails the way it
     * always has.
     */
    fun refuses(address: String, permitted: (host: String) -> Boolean): Boolean {
        val url = address.trim().toHttpUrlOrNull() ?: return false
        return !url.isHttps && !permitted(url.host)
    }
}
