# SDK changes

Amp needs some changes to `sdk/client` in Light's SDK — the `light-sdk`
submodule. They fall into two groups, and the difference matters.

**Additions** (§1–10) are small, self-contained and would be reasonable in the SDK
as it stands. Every one is marked in the source with
`SDK PATCH (additive, upstreamable)`.

**Workarounds** (§11–15) exist only because there is no supported route. Every one
is marked `SPIKE` or `TEMPORARY`, carries revert instructions in its own comment,
and must come out before a tool is submitted. What each is standing in for is
explained in [SDK-GAPS.md](SDK-GAPS.md).

Since the submodule restructure, every change here also exists as a literal
patch file in [`light-sdk-patch/`](../../light-sdk-patch/) at the repo root;
the build applies the set to the pristine `light-sdk` submodule automatically.
The patch files are the mechanical truth — this document is the narrative:
what each change is for, and how to revert it.

To find them all in a checkout:

```bash
grep -rn "SDK PATCH\|SPIKE\|TEMPORARY" light-sdk/sdk tool/src
```

### Counted, not remembered

This list undercounted for a while: it named three of the additions to
`LightAudioPlayer` and missed eleven more, which made the whole patch set look
about half its real size. Anyone planning to drop the patches — which is what
submitting a tool means — would have planned against the wrong number.

The way to check, rather than trust the prose, is to compare the public surface
against upstream and confirm nothing on any upstream branch already provides it.
Since the submodule restructure the two sides live in the same repository, so
this is one diff of the patched worktree against the pinned pristine commit:

```bash
git -C light-sdk fetch origin
diff <(grep -oE "^\s{4}(fun|val|var|suspend fun) [a-zA-Z]+" \
        light-sdk/sdk/client/src/main/kotlin/com/thelightphone/sdk/audio/LightAudioPlayer.kt | sort -u) \
     <(git -C light-sdk show origin/main:sdk/client/src/main/kotlin/com/thelightphone/sdk/audio/LightAudioPlayer.kt \
        | grep -oE "^\s{4}(fun|val|var|suspend fun) [a-zA-Z]+" | sort -u)
```

"Differs from upstream's `main`" is not the same as "we wrote it" — upstream
moves, and the submodule is pinned. A member is ours only if no upstream branch
has it:

```bash
git -C light-sdk branch -r | tr -d ' ' \
  | xargs -I{} git -C light-sdk grep -l "fun replaceRange" {} -- '*/LightAudioPlayer.kt'
```

Last counted 2026-08-30 against `3df3c24` — the 0.1.1 rebase re-placed every
member onto the rewritten upstream player by hand, so the count below is exact.
Two members the previous count listed, `pauseAtEndOfMediaItems` and
`skipSilence`, left the tree with the gapless trial and are gone from the count.

---

## Additions

### 1. `LightScreen.popToRoot()` / `LightActivity.popToRoot()`

Unwind to the initial screen. A tool with a tab bar visible on nested screens
needs it — `goBack()` only unwinds one level.

Two details matter in the implementation:

- Publish `currentScreen` **once**, after the loop. Assigning it per iteration
  makes every screen on the way down the current one for a frame, and since it
  drives composition each gets drawn on its way out — unwinding four screens
  flashes four pages past the user.
- Only the visible screen gets `notifyWillHide()`; the rest were never shown.

### 2. `LightAudioPlayer.onPlaybackError`

Forward Media3's `onPlayerError` to a tool-settable callback. Without it a
failed stream is indistinguishable from a pause, and an offline-capable player
can't fall back to a downloaded copy.

The `PlaybackException` itself is worth passing, not just the fact of an error:
its `errorCode` separates "the server answered with an error status" from "the
server never answered", which are different situations. Treating both as
"offline" takes a whole library down to downloads-only over one bad URL.

SDK 0.1.1 added an `error: StateFlow<LightAudioError?>` of its own — the right
idea, but it maps the exception into a type without the raw `errorCode`. This
callback stays until the flow carries the code, then folds into it.

### 3. `LightAudioPlayer.replaceRange(fromIndex, toIndex, items)`

Swap a range of the queue in one operation, leaving an item outside the range
playing. Reordering by repeated `moveItem` is a timeline update per move, and a
few hundred of those with a media session attached rebuilds the legacy queue —
artwork and all — each time, until the process runs out of memory.

Also the right way to re-request a stream at a new offset: removing and
re-adding the playing item moves the player's current index twice, which any
listener reads as a track change.

### 4. The rest of `LightAudioPlayer`

Eleven more members, all additive, none present on any upstream branch. They
are grouped here rather than given a section each because they are one thing:
the player the SDK ships can start a queue and move through it, and a music tool
also has to *edit* that queue, restore it, and be driven by the phone's own
controls.

| Member | Why a music tool needs it |
|---|---|
| `addItems`, `addItemAt` | Play Next and Add to Queue. Rebuilding the queue to append to it loses the playing item's position. |
| `removeItem`, `moveItem` | The queue editor — remove a track, drag one up. |
| `seekToIndex` | Tapping a row in the queue. |
| `setMediaQueueAt(items, index, positionMs)` | Restoring a saved queue mid-track. The position has to go in at prepare time: `seekTo` clamps to a duration that isn't known yet, so it lands on 0. |
| `deviceVolume`, `setSystemVolume` | The hardware rocker, which a tool has to service itself while casting — see `PlaybackController.handleVolumeKey`. |
| `repeatMode` | Repeat off / all / one. |
| `playWhenReady` | The caller's intent, distinct from `isPlaying`, which is false mid-rebuffer — a seek issued in that window read "paused" and never resumed. |
| `onItemRepeated` | The repeat-one wrap. The index flow can't carry it (the index doesn't change), and a server-seeked stream needs rebuilding at the track's start or it loops its own tail. |

None of them is novel — every one is a `Player` method or property that media3
already exposes and the SDK does not forward. That is also why they would survive
the detached-audio migration: upstream's detached player holds a `Player`, and
both `ExoPlayer` and `MediaController` implement it. See SDK-GAPS.md.

### 5. `buildDatabase` — `fallbackToDestructiveMigration`

A tool can't reach Room except through this helper (`android.content.Context` is
a blocked import), so it can't register migrations or a fallback. Without one,
shipping any schema change crashes every existing install on launch. Recreating
the tables is the right default: a tool's Room database is a rebuildable cache of
server state, not the system of record.

### 6. Splash icon centring

`sdk/client/src/main/res/drawable/loading_text_icon.xml` — the "loading…"
glyphs sit at y≈96 in a 240-unit viewport whose centre is 120, so the word
renders ~88px above centre on a 1240px panel (measured). The paths are wrapped
in `<group android:translateY="24.5">`; the paths themselves are untouched, so
reverting is deleting the group.

---

### 7. `setHandleAudioBecomingNoisy(true)`

One line on the `ExoPlayer.Builder`, and the default is wrong for anything that
plays audio: without it, Bluetooth disconnecting or headphones being unplugged
leaves playback running out of the phone's speaker. Media3 handles the
`ACTION_AUDIO_BECOMING_NOISY` broadcast itself once it's set.

A tool cannot do this for itself — it needs a `BroadcastReceiver`, and
`android.content` is blocked.

### 8. `LightAudioPlayer.isCurrentItemSeekable`

Whether the stream that actually arrived can be seeked within — true for a file
or any response carrying a length and byte ranges, false for a live chunked
transcode. Media3 already knows; the SDK didn't pass it on.

Without it a tool has to infer seekability from the format it *asked* for, and
that is wrong precisely when a server declines to transcode. Ask Navidrome for
mp3 when the file is already mp3 and it sends the file untouched — then ignores
`timeOffset`, having no encode to offset. The tool seeks the only way it thinks
it can, the server ignores it, and the track silently restarts while the
position readout insists the seek landed.

### 9. `hasRuntimePermission(permission)` — `LightPermissions.kt`

Whether this process holds a permission, from the process itself. The SDK's
`checkPermission` asks the server, and the server answers from its own policy
before it looks at the grant — `BlockedByServer` where the tool isn't meant to
have it, `Unknown` where it can't say. What decides whether a file can be read
is the grant, which the user may have made in Android's own settings without
the server hearing of it. A tool with only the server's answer told such a
phone to allow access it already had, and sent it to a prompt with nothing to
change. Exposes `LightServiceConnection.applicationContext` (internal) to ask.

### 10. `LightAudioNetworkPolicy` — `audio/LightAudioNetworkPolicy.kt`

A tool's say over whether its players may use the network at all: one
process-wide predicate, asked by a gating `DataSource` before every remote
connection the platform player opens and again as it reads. Both player
builders (`LightAudioPlayer`, `LightAudioService`) are built on it. Amp wires
it to `App.networkAllowed` at boot, which is how Wi-Fi Only becomes a wall
rather than a set of courtesy checks: a stream already in the queue used to
play on over cellular, and the next one along with it. Local files, assets and
other in-phone sources never ask — nor does a loopback address, which is how
Amp's own `StreamProxy` serves the player. While that proxy is on, this patch
is only the net under it: it still stands in front of any server URL handed to
the player directly (the proxy's self-test failed, or a queue built before it
came up). It goes when the proxy has proven itself on devices. Revert: drop the two `setMediaSourceFactory`
lines and the file; nothing else references it.

## Workarounds

Each of these is described in full — what it's standing in for, and how to
remove it — in [SDK-GAPS.md](SDK-GAPS.md). In brief:

### 11. ~~`audio/LightMediaService.kt`~~ — retired 31 Aug 2026

The background-audio spike is gone: Amp adopted the SDK's official
`detached-audio` capability. What replaced it in the patch set is
`audio-service.patch` — three *additive* lines on the SDK's own service
(device-volume control and becoming-noisy on its player, a `sessionActivity`
on its session), which belong with the additions above, not the workarounds.

### 12. `transfer/LightTransferService.kt` + manifest

`dataSync` foreground service so downloads aren't throttled ~9× when the tool is
backgrounded.

### 13. `LightActivity` — volume key pass-through

Lets hardware volume keys reach the system so the media session can route them,
which is what makes the rocker control a cast renderer rather than a silent
local player.

### 14. `display/LightDisplayColor.kt` + `LightActivity.onResume`/`onPause`

Switches LightOS's device-wide greyscale filter off while the tool is in front.
Needs `WRITE_SECURE_SETTINGS`, declared in the `debug` and `release` manifest
overlays but never in `src/main` — the plugin validates `src/main` only, so it
cannot reach a submitted build.

### 15. `cast/DlnaCast.kt`

SSDP discovery and SOAP control, written by hand because the sandbox blocks the
libraries that would normally do this.

---

`DlnaCast.allowsNetwork` is the one switch in front of everything it sends —
discovery, the two probes of the music server, the description fetch and every
SOAP call. Amp wires it to `LinkRules.mayUseLan`: on Wi-Fi, and in Wi-Fi Only
not on a metered hotspot. Off Wi-Fi a control call is a connection attempt to a
private address over cellular, and a probe is real data on a forbidden link.


## Seeing them as a diff

Since the submodule restructure this stopped being a problem worth a section:
**the patch set is literally a set of diffs**, in
[`light-sdk-patch/`](../../light-sdk-patch/), and the `light-sdk` submodule is
upstream's own history, pinned. So:

```bash
git -C light-sdk diff HEAD          # exactly our changes, nothing of Light's
scripts/sdk-patches.sh check        # the same, verified patch by patch
git -C light-sdk fetch origin
git -C light-sdk log --oneline HEAD..origin/main   # what upstream has that we don't
```

The in-source markers remain the cross-check — if a change isn't marked
`SDK PATCH`, `SPIKE` or `TEMPORARY`, it isn't ours:

```bash
grep -rn "SDK PATCH\|SPIKE\|TEMPORARY" light-sdk/sdk tool/src
```

(Before 30 Aug 2026 this repository carried a vendored copy of the SDK with no
shared history, and a diff against upstream conflated our changes with theirs.
If you are reading an old checkout, the markers are the only truth there.)

## Before submitting a tool

The workarounds are the thing to remove: delete the `spike-*.patch` files, plus
the spike-marked hunks of `audio-player.patch` and `light-activity.patch`. Each
carries its own revert steps, and this finds every one of them:

```bash
grep -rn "SPIKE\|TEMPORARY" light-sdk/sdk tool/src
```

The additions in §1–10 are a separate conversation with Light: they are useful to
any tool, not just this one, and are written to be upstreamable as they stand.
