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
| `audio-player.patch` | All additive since the detached-audio adoption retired the session spike. Incremental queue ops (`addItems`/`addItemAt`/`removeItem`/`moveItem`/`replaceRange`/`seekToIndex`), `setMediaQueueAt(items, index, positionMs)` so a queue can restore to an index *and* position, `repeatMode`, `isCurrentItemSeekable`, `playWhenReady` (intent vs. `isPlaying` mid-rebuffer), `onItemRepeated` (the repeat wrap, which the index flow can't carry), the device-volume surface (`deviceVolume`/`setSystemVolume`), `setHandleAudioBecomingNoisy` (headphones out ≠ speaker on), and the `onPlaybackError` callback (0.1.1's `error` flow maps the exception; the fallback logic needs the raw code — fold in when the flow exposes it). The attached player is built on the gated media-source factory from `audio-network-policy.patch`. |
| `audio-service.patch` | Three additions to 0.1.1's detached `LightAudioService`: its player gets `setDeviceVolumeControlEnabled` (the fader reads nothing through a controller without it) and `setHandleAudioBecomingNoisy`, and its session gets a `sessionActivity` so tapping the media surface opens the tool. Its player, too, is built on the gated media-source factory. |
| `audio-network-policy.patch` | `LightAudioNetworkPolicy` + `GatedDataSource` (new file, with its test): a tool's say over whether its players may use the network at all, asked before every remote connection a player opens and again as it reads. A tool with a Wi-Fi-only setting has no other way to keep the promise — the player fetches the next item ahead of time, and reconnects a dropped stream, over whatever network the phone is on. Both player builders (`audio-player.patch`, `audio-service.patch`) use it. |
| `connectivity.patch` | `LightConnectivity.observeNetworkStatus()` also registers the default-network callback. The listen request hears networks appear, change and vanish, but not the phone moving its default from a weak Wi-Fi to cellular while both stay up — the one change that flips `isMetered`. |
| `light-activity.patch` | **Mixed.** Upstreamable: `popToRoot()` unwind (publishes `currentScreen` once — assigning per pop flashed every dying screen). Spikes: volume keys fall through to the system instead of being forwarded to LightOS; `LightDisplayColor` foreground/background hooks. |
| `light-db-fallback.patch` | `buildDatabase` gains `fallbackToDestructiveMigration` — a tool can't register migrations, and a tool DB is a rebuildable cache. |
| `loading-icon-centering.patch` | Centres the splash word in its 240-unit viewport (it sat 87.5px high on the panel, measured). |
| `pop-to-root-screen.patch` | The public `LightScreen.popToRoot()` for the unwind in `light-activity.patch`. |
| `runtime-permission.patch` | `hasRuntimePermission()` — asks the process about a grant directly; the server answers from policy, not the grant, and can say Blocked for access the phone already allows. |
| `spike-background-transfer.patch` | `LightTransferService` (`dataSync` foreground service so long downloads aren't throttled 9× in the cached bucket) + its manifest entry and permissions. The background-*audio* half of this spike was retired when Amp adopted `capabilities = ["detached-audio"]`; the official answer for transfers is requested upstream as [light-sdk#187](https://github.com/lightphone/light-sdk/issues/187). |
| `spike-display-color.patch` | `LightDisplayColor` — lifts LightOS's global greyscale daltonizer while Amp is foreground, via `WRITE_SECURE_SETTINGS` (a `pm grant` the user runs; a clean no-op without it). |
| `spike-dlna-cast.patch` | `DlnaCast`, a dependency-free UPnP-AV control point (SSDP + SOAP over `java.net`), and its 14 parser tests. |

## Working on a patch

Edit the files inside `light-sdk/` directly — the applied state is the normal
state — then fold the result back into its patch:

```sh
scripts/sdk-patches.sh regen spike-dlna-cast.patch
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
