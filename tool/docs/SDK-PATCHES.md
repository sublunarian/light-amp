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

Last counted 2026-10-21 against 0.2.1 (`3a6b204`): eleven members on
`LightAudioPlayer` (`addItems`, `replaceRange`, `setMediaQueueAt`,
`repeatMode`, `playWhenReady`, `isCurrentItemSeekable`, `onItemRepeated`,
`onPlaybackError`, `playbackGain`, `deviceVolume`, `setSystemVolume`), none of
them on any upstream branch. 0.2.1's own queue edits replaced four that the
August count had (`addItemAt`, `removeItem`, `moveItem`, `seekToIndex`).

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
callback stays until the flow carries the code, then folds into it. 0.2.1 adds
the failed item's id to the error, but still not the code or the cause.

### 3. `LightAudioPlayer.replaceRange(fromIndex, toIndex, items)`

Swap a range of the queue in one operation, leaving an item outside the range
playing. Reordering by repeated `moveMediaItem` is a timeline update per move, and a
few hundred of those with a media session attached rebuilds the legacy queue —
artwork and all — each time, until the process runs out of memory.

Also the right way to re-request a stream at a new offset: removing and
re-adding the playing item moves the player's current index twice, which any
listener reads as a track change.

### 4. The rest of `LightAudioPlayer`

SDK 0.2.1 (light-sdk #127) brought the single-item queue edits, `addMediaItem`,
`removeMediaItem`, `moveMediaItem`, `replaceMediaItem` and
`seekToDefaultPosition`, and Amp uses those directly. The members below are
still missing upstream, all additive. They are grouped here rather than given a
section each because they are one thing: the player the SDK ships can start a
queue and move through it, and a music tool also has to edit that queue in bulk,
restore it, and be driven by the phone's own controls.

| Member | Why a music tool needs it |
|---|---|
| `addItems` | Add to Queue and a radio tail in one queue update. One `addMediaItem` per track rebuilds the session queue each time. |
| `setMediaQueueAt(items, index, positionMs)` | Restoring a saved queue mid-track. The position has to go in at prepare time: `seekTo` clamps to a duration that isn't known yet, so it lands on 0. |
| `deviceVolume`, `setSystemVolume` | The Output page's fader on This device, and matching the phone's level to a speaker's when a cast starts. A tool can't reach `AudioManager` itself. |
| `playbackGain` | ReplayGain: tracks play at an even loudness. Shaped like the SDK's own `speed`; on by default in Amp. |
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

### 7. `setHandleAudioBecomingNoisy(true)` and `setDeviceVolumeControlEnabled(true)`

Two lines on the `ExoPlayer.Builder` in `SdkExoPlayer.kt`, which builds both
players since 0.2.1. The first default is wrong for anything that plays audio:
without it, Bluetooth disconnecting or headphones being unplugged leaves
playback running out of the phone's speaker. Media3 handles the
`ACTION_AUDIO_BECOMING_NOISY` broadcast itself once it's set. The second lets a
detached controller hear device-volume changes, which keeps the fader in step.

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
connection the platform player opens and again as it reads. Both players are
built on it, through `buildSdkExoPlayer` in `SdkExoPlayer.kt`. Amp wires
it to `App.networkAllowed` at boot, which is how Wi-Fi Only becomes a wall
rather than a set of courtesy checks: a stream already in the queue used to
play on over cellular, and the next one along with it. Local files, assets and
other in-phone sources never ask — nor does a loopback address, which is how
Amp's own `StreamProxy` serves the player. While that proxy is on, this patch
is only the net under it: it still stands in front of any server URL handed to
the player directly (the proxy's self-test failed, or a queue built before it
came up). It goes when the proxy has proven itself on devices, or when Amp
streams through the proxy only, never handing the player a server URL. Revert:
drop the gated factory from `SdkExoPlayer.kt` and the file; nothing else
references it.

## Workarounds

Each of these is described in full — what it's standing in for, and how to
remove it — in [SDK-GAPS.md](SDK-GAPS.md). In brief:

### 11. ~~`audio/LightMediaService.kt`~~ — retired 31 Aug 2026

The background-audio spike is gone: Amp adopted the SDK's official
`detached-audio` capability. What replaced it in the patch set is
`audio-service.patch`: a `sessionActivity` on the SDK's own service, which
belongs with the additions above, not the workarounds. (Its player's
device-volume and becoming-noisy lines moved to `SdkExoPlayer.kt` with 0.2.1.)

### 12. `transfer/LightTransferService.kt` + manifest

`dataSync` foreground service so downloads aren't throttled ~9× when the tool is
backgrounded.

### 13. `LightActivity` — volume key pass-through

Volume keys a screen doesn't use fall through to Android, which turns media
volume while something plays, instead of going to LightOS, which turns the
ringer. While casting, Amp's screens take the keys themselves for the speaker
(`PlaybackController.handleVolumeKey`), which needs no patch.

### 14. `display/LightDisplayColor.kt` + `LightActivity.onResume`/`onPause`

Switches LightOS's device-wide greyscale filter off while the tool is in front.
Needs `WRITE_SECURE_SETTINGS`, declared in the `debug` and `release` manifest
overlays but never in `src/main` — the plugin validates `src/main` only, so it
cannot reach a submitted build.

### 15. ~~`cast/DlnaCast.kt`~~ — moved into the tool 15 Oct 2026

DLNA casting was never something the sandbox blocked: it only uses `java.net`
and coroutines, which the plugin allows, just like Amp's own Plex discovery. It
now lives in `tool/.../playback/output/dlna/DlnaCast.kt` and ships in store
builds. Its network switch, `DlnaCast.allowsNetwork`, is still wired to
`LinkRules.mayUseLan`, by `DlnaFamily.install`.


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
the spike-marked hunks of `light-activity.patch`. Each
carries its own revert steps, and this finds every one of them:

```bash
grep -rn "SPIKE\|TEMPORARY" light-sdk/sdk tool/src
```

The additions in §1–10 are a separate conversation with Light: they are useful to
any tool, not just this one, and are written to be upstreamable as they stand.
