# What the SDK needs

Amp works today, but five things it does are done *around* the SDK rather
than through it. Each one is a workaround that exists only because there is no
supported route, and each would come out the moment there is one.

They are listed here in the order they'd matter to any music tool, not just this
one. The first two are the ones that make a music player a music player.

**The first has since been answered upstream** — see the note under it. The
workaround is still what ships, because adopting the official route is a real
piece of work rather than a switch, and that work is costed below.

---

## 1. Background audio

**What a music tool needs:** to keep playing when the screen goes off or the
user opens another tool.

**What we do now:** a foreground `MediaSessionService` (`LightMediaService`) in
`sdk/client`, declared in the SDK manifest with `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`. `LightAudioPlayer` starts it and wraps the
player in a `MediaSession`.

**Why it can't be done in the tool:** the plugin sandbox blocks `android.app.*`
and `android.content.*`, which is everything needed to declare or start a
service. It has to live in the SDK.

**What would replace it:** `LightAudioPlayer` keeping playback alive on its own —
the tool asks for audio and the SDK owns the service. No new API surface needed
in the tool at all; it already just calls `play()`.

**Revert:** delete `LightMediaService.kt`, the `mediaSession` field and
`appContext` in `LightAudioPlayer`, and the service plus two permissions from
`sdk/client/src/main/AndroidManifest.xml`.

### Upstream shipped this (checked 2026-08-18)

`feat: detached audio playback` — upstream `71f7283`, merged as PR #148 on
2026-08-05, after the SDK copy vendored here. It is the supported route this
section asked for, and close to the shape asked for: the tool declares what it
wants and the SDK owns the service.

- `newPlayer(playback = LightAudioPlayback.Detached)` returns a handle backed
  by a `MediaController` against an SDK-owned `MediaSessionService`, rather
  than an in-process ExoPlayer. Both modes share one public surface for queue,
  transport, position, metadata and errors.
- Opted into with `capabilities = ["detached-audio"]` in `lighttool.toml`. The
  Gradle plugin expands that one entry into the foreground-service permissions,
  the service declaration, and a meta-data marker. The SDK checks the *marker*
  at runtime, not the permission, because any transitive dependency can
  contribute a permission and none of them says what the tool asked for.
- `release()` disconnects the handle without stopping playback; a reconnecting
  player finds a live session, awaits readiness, and reuses a non-empty queue
  rather than replacing it. The service stops itself 60 seconds after playback
  pauses with no handle open.
- Failures surface as SDK-owned `LightAudioError` values in both modes, so no
  media3 type reaches the public API.

**Why it hasn't been adopted yet.** The cost is not the capability line; it is
that four of the seven additions under *Smaller asks* live in
`LightAudioPlayer.kt`, and that is the file detached audio rewrote:

| | |
|---|---|
| This copy | 528 lines |
| Upstream | 360 lines |
| Differing | 456 lines |
| `onPlaybackError`, `replaceRange`, `setHandleAudioBecomingNoisy`, `isCurrentItemSeekable` present upstream | none of them |

So adopting it means re-applying four patches onto a substantially rewritten
file whose internals moved from an in-process player to a controller. Two of
those patches — `replaceRange` and `isCurrentItemSeekable` — reach into player
internals that may not survive a controller boundary at all, and
`PlaybackController` leans on both: the first for the gapless cast hand-off,
the second for the seek fallback. Neither has been tried against the detached
path.

Nothing here is a reason not to do it. It is a reason to do it deliberately,
with the background, cast hand-off and download-throttling cases all re-tested
on the device, rather than as a one-line change.

---

## 2. Background downloads

**What a music tool needs:** to finish downloading an album after the user
leaves the app.

**What we do now:** a `dataSync` foreground service (`LightTransferService`) held
for the duration of a transfer.

**Why it matters, measured:** without it, a backgrounded process drops to
Android's cached bucket and transfers are throttled roughly **ninefold** on the
LP3. That is the difference between an album arriving and an album not.

**What would replace it:** an SDK-owned transfer primitive — "fetch this URL to
this file, keep going if I'm backgrounded" — which is also what a podcast tool,
a photo tool or a mail tool would want.

**Revert:** delete `LightTransferService.kt`, its call sites in
`Downloader.kt`, and the service plus permission from the SDK manifest.

---

## 3. Hardware volume keys

**What a music tool needs:** the volume rocker to control what is playing —
including a remote renderer when casting, where Android's own media volume is
controlling a player that isn't making the sound.

**What we do now:** `LightActivity` lets volume keys fall through to the system,
and the `MediaSession` from (1) gives the OS a session to route them to. Screens
override `onKeyDown` and forward to `PlaybackController.handleVolumeKey`.

**Why it can't be done in the tool:** intercepting media keys needs a media
session, which needs a service, which the sandbox forbids.

**What would replace it:** either the SDK routing hardware keys to the focused
screen, or `LightAudioPlayer` owning the session as part of (1). Solving (1)
mostly solves this.

---

## 4. Colour

**What a music tool needs:** album art in colour. The LP3's greyscale is a
device-wide accessibility filter (`daltonizer`), not a property of the panel —
a screenshot comes out in full colour while the screen shows grey.

**What we do now:** `LightDisplayColor` writes `Settings.Secure` to switch the
filter off while the tool is in the foreground and restores it on the way out,
with a marker file so a crash can't strand the phone in colour. It needs
`WRITE_SECURE_SETTINGS`, granted once over adb. It is declared in the debug and
release manifest **overlays** — never in `src/main`, which is the only thing the
plugin validates, so a build submitted to Light cannot carry it. It ships in the
side-loaded release because the app has a switch for this, and a switch that
cannot work is worse than no switch; without the grant the permission is inert.

**What would replace it:** a per-tool exemption from the filter, requested in
`lighttool.toml` and granted by LightOS. A photo viewer, a camera tool or a
maps tool would all want the same thing.

**Revert:** delete `display/LightDisplayColor.kt`, its two calls in
`LightActivity.onResume`/`onPause`, the settings toggles in the app, and the
`WRITE_SECURE_SETTINGS` block from `tool/src/debug/AndroidManifest.xml`.

---

## 5. Casting

**What a music tool needs:** to play to a speaker on the network.

**What we do now:** `DlnaCast` — SSDP discovery and SOAP control written by
hand, no new dependencies, because the sandbox blocks the libraries that would
normally do this.

**What would replace it:** an SDK output-routing API covering Bluetooth, Cast
and UPnP, so a tool asks "play this here" rather than implementing a discovery
protocol.

**Revert:** delete `cast/DlnaCast.kt` and the DLNA section of
`PlaybackController.kt` (marked with its own banner comment).

---

## Smaller asks

Not workarounds — just things that were missing and are trivial to add. All seven
are already written as small additions to the SDK; see
[SDK-PATCHES.md](SDK-PATCHES.md).

None of the seven had been implemented upstream as of 2026-08-18, checked
against `upstream/main` at `522f94d`. Adopting a newer SDK does not shrink this
list; it only moves where the patches have to be re-applied.

| Gap | Why |
|---|---|
| `popToRoot()` | A tool with a tab bar visible on nested screens has to be able to unwind to the root. Without it, tapping a tab three levels deep can only go back one. |
| `onPlaybackError` | Media3 reports playback failures; the SDK swallowed them. A player that can't tell you it failed can't fall back to a downloaded copy. |
| `replaceRange()` | Reordering a queue by repeated `moveItem` rebuilds the media session's queue per move; a few hundred moves exhausts memory. One range swap does it once. |
| `fallbackToDestructiveMigration` | `buildDatabase` gives no way to handle a schema change, so any change crashes on upgrade. |
| Splash icon centring | The SDK's "loading…" glyph sits ~88px above centre on a 1240px panel, because the artwork is off-centre inside its own viewport. |
| `isCurrentItemSeekable` | Whether the stream that arrived can be seeked within. Guessing from the requested format is wrong whenever a server declines to transcode, and the failure is silent — the track restarts while the position claims otherwise. |
| `setHandleAudioBecomingNoisy(true)` | One line on the player's builder, and the default is wrong for audio: without it, Bluetooth disconnecting or headphones being unplugged leaves music playing out of the phone's speaker. Needs a `BroadcastReceiver` to do by hand, which the sandbox blocks. |

## Things that are genuinely blocked

Not gaps in the API but decisions above it:

**Physical button mapping.** The LP3's side buttons and dimmer wheel are behind
a LightOS token trust-gate. A side-loaded tool cannot bind them, and no patch to
the SDK changes that — it is enforced on the OS side. Upstream's key-forwarding
work (PR #114) does not change this: `onKeyDown`, `onKeyUp` and `onKeyMultiple`
are already identical here and upstream, and the gate is not an API gap.

**The stock Music tool's library.** LightOS keeps it in `com.lightos` private
storage, unreadable by any tool, and its contents do not appear in MediaStore
(verified: only ringtones are visible there). A tool cannot offer to play what
the built-in player already has. Amp reads its own folder instead, which is
why local files go in `Music/Amp`.
