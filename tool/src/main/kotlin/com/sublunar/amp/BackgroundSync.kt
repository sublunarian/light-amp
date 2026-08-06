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
    if (App.isReady) {
        runCatching { App.library.sync() }
    }
    LightJobResult.Success()
}
