# SDK changes

Amp needs some changes to `sdk/client` in Light's SDK — the `light-sdk`
submodule. They fall into two groups, and the difference matters.

**Additions** (§1–10) are small, self-contained and would be reasonable in the SDK
as it stands. Every one is marked in the source with `SDK PATCH`, and its
comments follow Light's rule for SDK code: only where needed, a line or two.

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

Last counted 2026-10-20 against 0.2.1 (`3a6b204`): eight members on
`LightAudioPlayer`, none of them on any upstream branch. Seven are left
(`replaceRange`, `setMediaQueueAt`, `repeatMode`, `playWhenReady`,
`playbackGain`, `deviceVolume`, `setSystemVolume`): `addItems` went on
23 Oct 2026, as `replaceRange` over the empty range at the end appends in the
same single queue update. 0.2.1's own queue
edits replaced four that the August count had (`addItemAt`, `removeItem`,
`moveItem`, `seekToIndex`), and three more went the same day
(`onPlaybackError`, `isCurrentItemSeekable`, `onItemRepeated`): Amp gets what
they told it from its own `StreamProxy` and restart copy now.

---

## Additions

### 1. ~~`LightScreen.popToRoot()` / `LightActivity.popToRoot()`~~ — dropped 20 Oct 2026

Amp unwinds with `goBack()` in a loop instead (`backToRoot` in `Nav.kt`),
stopping as soon as the library screen is showing, since one more back would
close Amp. The worry was that each page on the way down gets drawn for a frame,
and it doesn't: the pops all happen in one main-thread turn, so Compose draws
only the last one. Tested on the phone with a probe on the SDK's composition:
every unwind drew its destination and nothing in between.

### 2. ~~`LightAudioPlayer.onPlaybackError`~~ — dropped 20 Oct 2026

Amp needed the raw error for two facts the SDK's `error` flow leaves out: the
server's HTTP status (a 5xx means try the original's other address) and
whether the app's own rule refused the stream. Every stream now goes through
`StreamProxy`, which saw both, so Amp reads the flow for what failed and asks
the door what the server said (`StreamProxy.lastOutcome`).

### 3. `LightAudioPlayer.replaceRange(fromIndex, toIndex, items)`

Swap a range of the queue in one operation, leaving an item outside the range
playing. Reordering by repeated `moveMediaItem` is a timeline update per move, and a
few hundred of those with a media session attached rebuilds the legacy queue —
artwork and all — each time, until the process runs out of memory.

An empty range inserts, so the same call appends: Add to Queue, and the window
of the queue Amp's player holds (at most 500 rows of a queue of any length),
which slides along by replacing its ends.

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
| `setMediaQueueAt(items, index, positionMs)` | Restoring a saved queue mid-track. The position has to go in at prepare time: `seekTo` clamps to a duration that isn't known yet, so it lands on 0. |
| `deviceVolume`, `setSystemVolume` | The Output page's fader on This device, and matching the phone's level to a speaker's when a cast starts. A tool can't reach `AudioManager` itself. |
| `playbackGain` | ReplayGain: tracks play at an even loudness. Shaped like the SDK's own `speed`; on by default in Amp. |
| `repeatMode` | Repeat off / all / one. |
| `playWhenReady` | The caller's intent, distinct from `isPlaying`, which is false mid-rebuffer — a seek issued in that window read "paused" and never resumed. |

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

### 6. ~~Splash icon centring~~ — dropped 20 Oct 2026

The "loading…" word sits ~88px above centre on the splash, as it does in every
SDK tool. Light's designer owns the splash (light-sdk #41), and a store build
couldn't carry the fix anyway, so Amp now looks like the other tools. The patch
wrapped the paths of `loading_text_icon.xml` in
`<group android:translateY="24.5">`, if it is ever wanted back.

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

### 8. ~~`LightAudioPlayer.isCurrentItemSeekable`~~ — dropped 20 Oct 2026

Whether the stream that arrived can be seeked within. Guessing from the format
Amp *asked* for is wrong when a server declines to transcode, and fails
silently. `StreamProxy` sees whether the server sent a length, which is what
the player decides on too, so Amp asks the door instead.

### 9. `hasRuntimePermission(permission)` — `LightPermissions.kt`

Whether this process holds a permission, from the process itself. The SDK's
`checkPermission` asks the server, and the server answers from its own policy
before it looks at the grant — `BlockedByServer` where the tool isn't meant to
have it, `Unknown` where it can't say. What decides whether a file can be read
is the grant, which the user may have made in Android's own settings without
the server hearing of it. A tool with only the server's answer told such a
phone to allow access it already had, and sent it to a prompt with nothing to
change. Exposes `LightServiceConnection.applicationContext` (internal) to ask.

### 10. ~~`LightAudioNetworkPolicy`~~ — dropped 20 Oct 2026

It gated the player's own HTTP fetches, the one thing Wi-Fi Only couldn't
otherwise reach. Amp now streams only through `StreamProxy`: the player is
never handed a server's address, the door asks the rule at every request and
while it serves, and when the door isn't up nothing streams and the screen
says so.

## Workarounds

Each of these is described in full — what it's standing in for, and how to
remove it — in [SDK-GAPS.md](SDK-GAPS.md). In brief:

### 11. ~~`audio/LightMediaService.kt`~~ — retired 31 Aug 2026

The background-audio spike is gone: Amp adopted the SDK's official
`detached-audio` capability. What replaced it in the patch set was
`audio-service.patch`: a `sessionActivity` on the SDK's own service, so that
tapping a media surface opens the tool. Dropped 20 Oct 2026, because nothing on
LightOS reads it. (Its player's device-volume and becoming-noisy lines moved to
`SdkExoPlayer.kt` with 0.2.1.)

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

The workarounds are the thing to remove: delete the `spike-*.patch` files and
`light-activity.patch`, which is all spike now. Each
carries its own revert steps, and this finds every one of them:

```bash
grep -rn "SPIKE\|TEMPORARY" light-sdk/sdk tool/src
```

The additions in §1–10 are a separate conversation with Light: they are useful to
any tool, not just this one, and are written to be upstreamable as they stand.
