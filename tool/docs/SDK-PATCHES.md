# SDK changes

Amp needs some changes to `sdk/client` in Light's repository. They fall into
two groups, and the difference matters.

**Additions** (§1–5) are small, self-contained and would be reasonable in the SDK
as it stands. Every one is marked in the source with
`SDK PATCH (additive, upstreamable)`.

**Workarounds** (§6–10) exist only because there is no supported route. Every one
is marked `SPIKE` or `TEMPORARY`, carries revert instructions in its own comment,
and must come out before a tool is submitted. What each is standing in for is
explained in [SDK-GAPS.md](SDK-GAPS.md).

To find them all in a checkout:

```bash
grep -rn "SDK PATCH\|SPIKE\|TEMPORARY" sdk/ tool/src
```

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

### 3. `LightAudioPlayer.replaceRange(fromIndex, toIndex, items)`

Swap a range of the queue in one operation, leaving an item outside the range
playing. Reordering by repeated `moveItem` is a timeline update per move, and a
few hundred of those with a media session attached rebuilds the legacy queue —
artwork and all — each time, until the process runs out of memory.

Also the right way to re-request a stream at a new offset: removing and
re-adding the playing item moves the player's current index twice, which any
listener reads as a track change.

### 4. `buildDatabase` — `fallbackToDestructiveMigration`

A tool can't reach Room except through this helper (`android.content.Context` is
a blocked import), so it can't register migrations or a fallback. Without one,
shipping any schema change crashes every existing install on launch. Recreating
the tables is the right default: a tool's Room database is a rebuildable cache of
server state, not the system of record.

### 5. Splash icon centring

`sdk/client/src/main/res/drawable/loading_text_icon.xml` — the "loading…"
glyphs sit at y≈96 in a 240-unit viewport whose centre is 120, so the word
renders ~88px above centre on a 1240px panel (measured). The paths are wrapped
in `<group android:translateY="24.5">`; the paths themselves are untouched, so
reverting is deleting the group.

---

## Workarounds

Each of these is described in full — what it's standing in for, and how to
remove it — in [SDK-GAPS.md](SDK-GAPS.md). In brief:

### 6. `audio/LightMediaService.kt` + manifest

Foreground `MediaSessionService` so audio survives the screen going off.
Also declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

### 7. `transfer/LightTransferService.kt` + manifest

`dataSync` foreground service so downloads aren't throttled ~9× when the tool is
backgrounded.

### 8. `LightActivity` — volume key pass-through

Lets hardware volume keys reach the system so the media session can route them,
which is what makes the rocker control a cast renderer rather than a silent
local player.

### 9. `display/LightDisplayColor.kt` + `LightActivity.onResume`/`onPause`

Switches LightOS's device-wide greyscale filter off while the tool is in front.
Needs `WRITE_SECURE_SETTINGS`, declared in the `debug` and `release` manifest
overlays but never in `src/main` — the plugin validates `src/main` only, so it
cannot reach a submitted build.

### 10. `cast/DlnaCast.kt`

SSDP discovery and SOAP control, written by hand because the sandbox blocks the
libraries that would normally do this.

---

## Seeing them as a diff

This repository carries Light's SDK with our changes on top, so upstream is one
remote away and the diff is the patch set — always accurate, with nothing to keep
in step by hand:

```bash
git remote add upstream https://github.com/lightphone/light-sdk
git fetch upstream
git diff upstream/main -- sdk/
```

The markers in the source (`SDK PATCH`, `SPIKE`, `TEMPORARY`) are the other half
of keeping this honest — if a change isn't marked, it isn't ours.

## Before submitting a tool

The workarounds are the thing to remove. Each carries its own revert steps, and
this finds every one of them:

```bash
grep -rn "SPIKE\|TEMPORARY" sdk/ tool/src
```

The additions in §1–5 are a separate conversation with Light: they are useful to
any tool, not just this one, and are written to be upstreamable as they stand.
