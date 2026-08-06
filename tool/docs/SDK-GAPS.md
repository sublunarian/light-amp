# What the SDK needs

Amp works today, but five things it does are done *around* the SDK rather
than through it. Each one is a workaround that exists only because there is no
supported route, and each would come out the moment there is one.

They are listed here in the order they'd matter to any music tool, not just this
one. The first two are the ones that make a music player a music player.

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

Not workarounds — just things that were missing and are trivial to add. All five
are already written as small additions to the SDK; see
[SDK-PATCHES.md](SDK-PATCHES.md).

| Gap | Why |
|---|---|
| `popToRoot()` | A tool with a tab bar visible on nested screens has to be able to unwind to the root. Without it, tapping a tab three levels deep can only go back one. |
| `onPlaybackError` | Media3 reports playback failures; the SDK swallowed them. A player that can't tell you it failed can't fall back to a downloaded copy. |
| `replaceRange()` | Reordering a queue by repeated `moveItem` rebuilds the media session's queue per move; a few hundred moves exhausts memory. One range swap does it once. |
| `fallbackToDestructiveMigration` | `buildDatabase` gives no way to handle a schema change, so any change crashes on upgrade. |
| Splash icon centring | The SDK's "loading…" glyph sits ~88px above centre on a 1240px panel, because the artwork is off-centre inside its own viewport. |

## Things that are genuinely blocked

Not gaps in the API but decisions above it:

**Physical button mapping.** The LP3's side buttons and dimmer wheel are behind
a LightOS token trust-gate. A side-loaded tool cannot bind them, and no patch to
the SDK changes that — it is enforced on the OS side.

**The stock Music tool's library.** LightOS keeps it in `com.lightos` private
storage, unreadable by any tool, and its contents do not appear in MediaStore
(verified: only ringtones are visible there). A tool cannot offer to play what
the built-in player already has. Amp reads its own folder instead, which is
why local files go in `Music/Amp`.
