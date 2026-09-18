package com.sublunar.amp

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

/**
 * Periodic library refresh. Runs while the tool's process is alive; on a fresh
 * process (no booted app) it no-ops, since the next launch syncs anyway.
 */
@LightJob("library-sync")
val librarySyncJob: LightJobHandler = { _, _ ->
    // Bulk work, by the same test as everything else that is: on a metered
    // link outside Make it Hurt it waits. Left alone, this walked the whole
    // album index every half hour of a cellular listening session, against
    // the stream being played — the launch sync's floor did not cover it.
    if (App.isReady && App.heavyDataAllowed()) {
        runCatching { App.library.sync() }
    }
    LightJobResult.Success()
}
