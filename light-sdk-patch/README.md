# light-sdk-patch

Every change Amp makes to Light's SDK, as one patch file per area, applied to
the pristine `light-sdk` submodule. The build applies the whole set
automatically when the submodule is clean (see `settings.gradle.kts`) and then
*leaves it applied*, so the sources you read and debug are the sources that
built. `scripts/sdk-patches.sh` has `apply` / `revert` / `check` / `regen`.

Two kinds of change live here, and the distinction is the whole point:

- **SDK PATCH** — additive and meant to be upstreamed to
  [lightphone/light-sdk](https://github.com/lightphone/light-sdk). These stay
  until Light merges an equivalent.
- **SPIKE** (files prefixed `spike-`) — deliberately outside the SDK's rules,
  possible only because the sandbox plugin exempts SDK modules from source
  validation. These are deleted before any store submission. Full revert steps
  are in [tool/docs/SDK-PATCHES.md](../tool/docs/SDK-PATCHES.md).

| Patch | What it is |
|---|---|
| `audio-player.patch` | All additive. SDK 0.2.1 brought single-item queue edits (`addMediaItem`, `removeMediaItem`, `moveMediaItem`, `replaceMediaItem`, `seekToDefaultPosition`), which Amp uses directly. What's left: multi-item `replaceRange` (one queue update instead of one per item; an empty range inserts, so it appends too), `setMediaQueueAt(items, index, positionMs)` so a queue can restore to an index *and* position, `repeatMode`, `playWhenReady` (intent vs. `isPlaying` mid-rebuffer), `playbackGain` (ReplayGain), and the device-volume surface (`deviceVolume`/`setSystemVolume`). Also the player defaults in `SdkExoPlayer.kt`, where 0.2.1 builds both the attached and the detached player: device-volume control and `setHandleAudioBecomingNoisy` (headphones out ≠ speaker on). |
| `light-activity.patch` | Spikes only: volume keys fall through to the system instead of being forwarded to LightOS; `LightDisplayColor` foreground/background hooks. |
| `light-db-fallback.patch` | `buildDatabase` gains `fallbackToDestructiveMigration` — a tool can't register migrations, and a tool DB is a rebuildable cache. |
| `runtime-permission.patch` | `hasRuntimePermission()` — asks the process about a grant directly; the server answers from policy, not the grant, and can say Blocked for access the phone already allows. |
| `spike-background-transfer.patch` | `LightTransferService` (`dataSync` foreground service so long downloads aren't throttled 9× in the cached bucket) + its manifest entry and permissions. The background-*audio* half of this spike was retired when Amp adopted `capabilities = ["detached-audio"]`; the official answer for transfers is requested upstream as [light-sdk#187](https://github.com/lightphone/light-sdk/issues/187). |
| `spike-display-color.patch` | `LightDisplayColor` — lifts LightOS's global greyscale daltonizer while Amp is foreground, via `WRITE_SECURE_SETTINGS` (a `pm grant` the user runs; a clean no-op without it). |

## Working on a patch

Edit the files inside `light-sdk/` directly — the applied state is the normal
state — then fold the result back into its patch:

```sh
scripts/sdk-patches.sh regen spike-display-color.patch
```

`check` tells you which patch a stray submodule edit belongs to (everything
`ok` except the one you touched). A patch must touch only files no other patch
touches; that is what keeps `apply` order-independent and `regen` safe.

## Moving the submodule pin

```sh
scripts/sdk-patches.sh revert          # pristine again
git -C light-sdk checkout <new-sha>
./gradlew :tool:assembleRelease        # auto-applies; fails loudly on drift
```

A patch that no longer applies is rebased by hand: apply what survives, re-make
the rest against the new sources, `regen`. Never resolve drift by weakening
what the patch does — [no silent fallbacks](../tool/docs/ARCHITECTURE.md).
