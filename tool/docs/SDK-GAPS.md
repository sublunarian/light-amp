# What Amp needs from the SDK

Amp ships today, but parts of it are built around the SDK because there is no
supported route. Each item below is a workaround we would delete the day an API
exists. Checked against `upstream/main` at `c86e40a`, 19 Aug 2026.

## Workarounds we would drop

### Background audio

- **Need:** keep playing when the screen goes off or another tool opens.
- **Now:** a foreground `MediaSessionService` (`LightMediaService`) in
  `sdk/client`, started by `LightAudioPlayer`.
- **Why not in the tool:** the sandbox blocks `android.app.*` and
  `android.content.*`, so a tool cannot declare or start a service.
- **Answered upstream** by detached audio (PR #148). See below.
- **Revert:** delete `LightMediaService.kt`, the `mediaSession` and `appContext`
  fields in `LightAudioPlayer`, and the service and two permissions from the SDK
  manifest.

### Background downloads

- **Need:** finish downloading an album after the user leaves the app.
- **Now:** a `dataSync` foreground service (`LightTransferService`) held for the
  length of a transfer.
- **Measured:** without it, a backgrounded tool is throttled about 9x on the LP3.
- **Would replace it:** an SDK transfer primitive — fetch this URL to this file,
  keep going in the background. A podcast or photo tool would want the same.
- **Revert:** delete `LightTransferService.kt`, its calls in `Downloader.kt`, and
  the service and permission from the SDK manifest.

### Hardware volume keys

- **Need:** the rocker changes playback volume, with the LightOS volume panel.
- **Fixed in LightOS 572:** the keys reach a side-loaded tool now. Before, the
  token was rejected and the press did nothing.
- **Still wrong:** forwarded keys move the **ringer**, even with music playing.
  LightOS chooses the stream in its own process, where nothing is playing.
- **Nothing on our side can say otherwise:** `DeviceKeyEvent` carries keyCode,
  action and characters — no stream. Setting `volumeControlStream` on the
  foreground activity does not cross the process boundary (tried, 20 Aug 2026).
- **Now:** we keep volume keys away from the server and let them fall through to
  the system, with `volumeControlStream = STREAM_MUSIC` so the stream is right.
  That loses the LightOS volume panel.
- **Would fix it:** route forwarded volume keys to the active media session, or
  carry the foreground tool's `volumeControlStream` in `DeviceKeyEvent`.

### Colour

- **Need:** album art in colour. The LP3's greyscale is a device-wide
  accessibility filter, not the panel — a screenshot comes out in full colour.
- **Now:** `LightDisplayColor` writes `Settings.Secure` to switch the filter off
  while Amp is in the foreground, with a marker file so a crash cannot strand the
  phone in colour. Needs `WRITE_SECURE_SETTINGS`, granted once over adb.
- **Not in a submitted build:** it lives in the debug and release manifest
  overlays, never in `src/main`.
- **Would replace it:** a per-tool exemption requested in `lighttool.toml`. A
  photo, camera or maps tool would want it too.
- **Revert:** delete `display/LightDisplayColor.kt`, its two calls in
  `LightActivity`, the app's toggles, and the permission from the debug manifest.

### Bluetooth

- **Need:** see paired devices, connect and disconnect, and start pairing, from
  the tool's own output screen.
- **Now:** nothing. Amp's Output screen lists "This device" and any network
  speakers it finds, with a Bluetooth row that is permanently disabled and a note
  saying Light handles the routing.
- **Why not in the tool:** `BluetoothManager` needs `getSystemService`, which the
  sandbox blocks. So does handing off — opening the system Bluetooth screen needs
  `Intent` and `startActivity`, both blocked too. There is no route at all, not
  even a bad one.
- **Would replace it:** either would help, smallest first —
  - an SDK call that opens the system Bluetooth screen, or
  - paired devices with connect and disconnect, alongside the audio API.
- **Why it belongs in a music tool:** choosing where sound comes out is part of
  playing it. Today, connecting headphones means leaving Amp for Settings and
  coming back.

### Casting

- **Need:** play to a speaker on the network.
- **Now:** `DlnaCast` — SSDP discovery and SOAP control written by hand, since the
  sandbox blocks the usual libraries.
- **Would replace it:** an output-routing API covering Bluetooth, Cast and UPnP.
- **Revert:** delete `cast/DlnaCast.kt` and the DLNA section of
  `PlaybackController.kt`.

## Answered upstream, not yet adopted

### Detached audio — PR #148, merged 5 Aug 2026

The supported route for background audio: `newPlayer(playback = Detached)` plus
`capabilities = ["detached-audio"]` in `lighttool.toml`.

Not adopted yet because it rewrote `LightAudioPlayer.kt`, which is where most of
our additions live:

- Our copy 528 lines, upstream 360, differing 456.
- `onPlaybackError`, `replaceRange`, `setHandleAudioBecomingNoisy` and
  `isCurrentItemSeekable` are ours, none upstream.
- `replaceRange` and `isCurrentItemSeekable` reach into player internals that may
  not survive a controller boundary. `PlaybackController` uses the first for the
  gapless cast hand-off and the second for the seek fallback.

Worth doing deliberately, with background playback, cast hand-off and download
throttling re-tested on the device.

### Connectivity — PR #166, merged 17 Aug 2026

`LightConnectivity` gives `isConnected`, `isWifi`, `isMetered` and an observer.
That replaces our `Connectivity.kt`, which polls network interfaces every five
seconds because `getSystemService` is blocked. We will drop it when we take a
newer SDK.

## Smaller additions

Already written as patches; see [SDK-PATCHES.md](SDK-PATCHES.md), which is the
authority. None were upstream as of `c86e40a`.

| Gap | Why |
|---|---|
| `popToRoot()` | A tab bar visible on nested screens has to unwind to the root. Without it, tapping a tab three levels deep only goes back one. |
| `onPlaybackError` | Media3 reports failures; the SDK swallowed them. A player that cannot report a failure cannot fall back to a downloaded copy. |
| Room migrations | `buildDatabase` exposes no builder, so a tool cannot register a migration. We patched in `fallbackToDestructiveMigration`, which means every schema change wipes the user's cache and download index. |
| `replaceRange()` | Reordering by repeated `moveItem` rebuilds the session queue per move; a few hundred moves exhausts memory. |
| `isCurrentItemSeekable` | Whether the stream that arrived can be seeked. Guessing from the requested format is wrong when a server declines to transcode, and it fails silently. |
| `setHandleAudioBecomingNoisy(true)` | One line on the builder. Without it, unplugging headphones leaves music playing out of the speaker. Doing it by hand needs a `BroadcastReceiver`, which the sandbox blocks. |
| Playback state | The player exposes position, duration and `isPlaying`, but not the state behind them. A tool cannot tell a finished queue from a pause, so we infer it from position against duration. |
| `TextInputKeyboardCallback` | `LightEmbeddedLp3Keyboard` is public and usable on a tool's own screen, but the callback that turns keys into edits is `internal`. We copied ~60 lines of it, including surrogate-aware backspace, to put a keyboard under our search results. |
| Splash icon centring | The loading glyph sits ~88px above centre on a 1240px panel; the artwork is off-centre inside its own viewport. |

## Blocked above the API

- **Physical buttons.** The side buttons and dimmer wheel are behind a LightOS
  token trust-gate. A side-loaded tool cannot bind them and no SDK patch changes
  that.
- **The stock Music tool's library.** It lives in `com.lightos` private storage
  and does not appear in MediaStore, so a tool cannot offer to play it. Amp reads
  its own folder, which is why local files go in `Music/Amp`.
