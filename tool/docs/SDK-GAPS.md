# What Amp needs from the SDK

Amp ships today, but parts of it are built around the SDK because there is no
supported route. Each item below is a workaround we would delete the day an API
exists. Checked against `upstream/main` at `311aa2d` (SDK 0.2.0 and three
merges after it, 8 Oct 2026), which is where the `light-sdk` submodule is
pinned. 0.2.0 closed one gap, plain `http://` (see Data modes below); the
rest stand.

## Workarounds we would drop

### Background audio — CLOSED, adopted 31 Aug 2026

The gap that started the spikes is gone: Amp declares
`capabilities = ["detached-audio"]` and plays through the SDK's own
`LightAudioService`. The `LightMediaService` spike was deleted in full.
What remains ours: the service's player needed `setDeviceVolumeControlEnabled`
and `setHandleAudioBecomingNoisy`, and its session a `sessionActivity` — three
additive lines, in `audio-service.patch`, written to be upstreamed. One
behavioural trade accepted: the SDK stops a paused, unheld session after 15
minutes, where the spike held on indefinitely.

### Background downloads

- **Need:** finish downloading an album after the user leaves the app.
- **Now:** a `dataSync` foreground service (`LightTransferService`) held for the
  length of a transfer.
- **Measured:** without it, a backgrounded tool is throttled about 9x on the LP3.
- **Would replace it:** an SDK transfer primitive — fetch this URL to this file,
  keep going in the background. A podcast or photo tool would want the same.
- **Requested upstream:**
  [lightphone/light-sdk#187](https://github.com/lightphone/light-sdk/issues/187)
  (31 Aug 2026) proposes a `background-transfer` capability in detached audio's
  shape, with the throttle measurements attached.
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

### Allow-listed libraries in the store build

- **Need:** a dependency on the plugin's allow-list builds in Light's store
  builder too.
- **Was:** the builder ran offline, and its cache held only what the SDK's
  sample tool resolves. `material-icons-extended` is allow-listed but was not
  in it, so the 46 glyphs Amp uses are vendored in
  `ui/components/MaterialGlyphs.kt` and held against the library by
  `MaterialGlyphsTest`.
- **Since SDK 0.2.0:** the builder fetches anything else a tool declares
  through a build-time Maven proxy (`light-sdk/builder/README.md`), so the
  artifact should now resolve there. Kept vendored until a real dashboard
  build proves it.
- **To revisit:** after Amp's first build on Light's dashboard. If it
  resolved `material-icons-extended`, revert as below.
- **Revert:** delete `MaterialGlyphs.kt`, and restore the `implementation` line
  in `tool/build.gradle.kts` and the icon imports in `Primitives.kt`.

### The detached player is bound to the activity, not the app

- **Need:** a detached player that outlives its screens — the feature's whole
  point — without the process dying when the activity behind it goes.
- **The fault:** `DefaultLightAudio.newPlayer` hands `sealedActivity.activity`
  to `LightAudioPlayer`, which builds the media3 `MediaController` against it
  (`connectDetachedPlayer`). media3 keeps that Context raw and unbinds through
  it. Proven on the phone on 9 Oct 2026, every step in the log with the same
  connection id: Android destroys the activity while the process lives, and
  logs that it has *leaked ServiceConnection
  …MediaControllerImplBase$SessionServiceConnection*, dropping the binding.
  Amp keeps the handle. Paused, media3's 10-minute foreground timeout lets the
  service out of the foreground, Android stops it as an idle background
  service (`am_stop_idle_service`), media3 releases the controller and posts
  its unbind against the dead activity, and the process dies with
  `IllegalArgumentException: Service not registered` on the main looper,
  where no caller can catch it. The SDK's own 15-minute idle stop never fires
  while a handle is open. Seen three times in four days in ordinary use
  (6–8 Oct 2026), noticed on a reopen because the process was frozen.
  Repro: open Amp, play and pause, Home, then
  `adb shell am start -n com.sublunar.amp/com.thelightphone.sdk.LightActivity -f 0x10008000`
  (NEW_TASK|CLEAR_TASK), and wait out the 10 minutes. (A 2026-09-22
  diagnosis blamed a double unbind; media3 1.10.1 cannot do that.)
- **Ask Light to fix it — and first, if the mitigation below is ever judged
  to break SDK compliance.** The mitigation absorbs an exception on the main
  thread, which a reviewer could read as hiding crashes; the author's rule is
  that the moment it counts against the tool, getting this fixed upstream is
  the priority. Not yet reported (no issue on light-sdk as of 9 Oct 2026).
- **Would replace it:** one line in the SDK — build the detached controller
  against `context.applicationContext`, as `LightServiceConnection` and
  `LightNfcReader` already do for their own bindings. Also worth asking: let a
  handle observe its controller disconnecting; today a dead handle stays Ready.
- **Now:** two halves, both in `tool/`. `playback/LateUnbindGuard.kt`,
  installed as the first line of `App.boot`, posts one message that runs the
  main looper inside a try. It absorbs an `IllegalArgumentException` only when
  `isLateMediaUnbind` (`playback/LateUnbind.kt`) recognises it — the
  *Service not registered: …MediaControllerImplBase$SessionServiceConnection@*
  message, thrown in `LoadedApk.forgetServiceDispatcher` under
  `ContextImpl.unbindService`, then media3's controller frames and directly
  beneath them `Handler.handleCallback` — and rethrows everything else as it
  was. Each absorb is logged under `AmpLife` with a count, the full stack the
  first time. It matches class names, so it relies on the `-dontobfuscate` in
  `assets/r8/keep-names.txt`, and `scripts/release-mapping-check.sh` fails a
  release build whose mapping renamed `SessionServiceConnection` or moved
  media3's posted release out of a `MediaControllerImplBase` class. It is run
  after every local release build, before tagging, since those are the builds
  that ship; `store-build-check.sh` runs it on the store build too, which only
  gets that far once the store build compiles. The other half:
  `PlaybackController.attach` replaces a handle still held when a new activity
  boots — releases it and makes the new one in the same main-thread turn,
  with no back-out teardown, so playback, the queue, the gain and any cast
  carry on (`AmpLife: new activity: handle moved over`). Around it: a pending
  start plays whatever handle is current when the server answers, an old
  screen's back-out can't release a newer activity's handle, and the deferred
  Main blocks that steer the service as a whole read the handle again instead
  of using the one they captured. The ones tied to the handle they checked
  still drop on a change: a missed seek's reload (`verifyNativeSeek`,
  `serverSeek`) and the stream-died retry. The queue's re-resolution drops its
  edits too, but puts its memo back to what the service holds and asks again,
  so the next connected handle still gets them. And a new handle's collectors
  wait for it to connect, where they used to copy its placeholders over the
  screen: a reopen or a handover flashed 0:00, showed paused (a press then
  resumed instead of pausing), and on the last second of the last track could
  rewind the live service. A service that moved to another track while no
  handle listened is landed on before they start, at its own position
  (`connectLanding`); the index collector used to zero it, and paused, the
  reopen showed 0:00 for good. A handle the SDK releases because its
  connection failed is dropped (`AmpLife: player handle released before it
  connected`) rather than kept to throw at the next press.
- **Revert,** once the pinned SDK builds the detached controller from
  `context.applicationContext`: delete `playback/LateUnbind.kt`,
  `playback/LateUnbindGuard.kt`, `LateUnbindTest.kt` and
  `scripts/release-mapping-check.sh`, and take the mapping check out of the
  release steps; take `LateUnbindGuard.install()`, its comment and import out
  of `App.boot`; drop the late-unbind paragraph above `-dontobfuscate` in
  `keep-names.txt` (the line stays, for readable traces); drop the call to the
  mapping check from `store-build-check.sh`, with its comment and header
  paragraph, and set its help range back to `2,27p`. The handover can stay,
  as it is harmless with a fixed SDK; reword the `attach` and `handOver` KDoc,
  which justify it by the stranded activity binding and the crash
  `LateUnbindGuard` absorbs — with a fixed SDK it is a tidy swap. To drop it
  as well, `App.boot` calls `playback.bind` again (public, with its
  `if (player != null) return` back), `attach`, `handOver`, `handleOwner` and
  the first-reading rule in the `deviceVolume` collector go, and `release`,
  `App.shutdown` and `BootScreen` lose the owner. The handover's mentions go
  with it: the `player` KDoc, `[handOver]` in the `handleJob` KDoc, the last
  sentence of the `bindJob` KDoc, the parenthetical in `release`, the
  `attachHandle` KDoc, and the comments in `attachHandle` that name it
  (`git grep -n -i handover` on `PlaybackController.kt` lists them all; the
  one in `fallBackOffline` is a Wi-Fi handover and stays), and the handover
  in the player-handle paragraph of `ARCHITECTURE.md`. The kept re-reads,
  and `reresolveQueue`'s dropped edits, give a new activity taking the player
  over as a reason; without the handover it is a back-out releasing the
  handle in between. Keep the rest
  either way: `@Volatile player`, the current-handle reads, the gain seed with
  the gain collector's `idx < 0` skip, the `replayGain` collector inside
  `coreReady`, the handle collectors waiting for the connection, the landing
  at connect, `reresolveQueue` putting its memo back, and `handleLost` each
  fix something a plain reopen after a back-out gets wrong too (a handle read
  across threads or after its release, a jump to full level, one more
  collector per activity, the 0:00 flash, 0:00 on a service that moved on, a
  re-resolution lost to a back-out, a crash after a failed connection), and
  the `bind`/`attachHandle` split is where that wait lives.

### Copy and paste in text entry — light-sdk#45, in progress at Light

- **Need:** paste what was copied in LightOS (Notes, Messages) into a field —
  Bandcamp's generated logins are too long to type — through LightOS's own
  hold-the-text menu of Copy, Paste and Clear.
- **Now:** LightOS keeps its own clipboard and no service method reaches it, so
  Amp offers Clear alone. To draw even that, `TextEntryScreen` carries its own
  copy of `LightTextInputEditor`'s layout, whose text area takes every touch and
  offers no hold; the menu sits in `LightEmbeddedLp3Keyboard`'s `overlay`.
- **Would replace it:** #45 — copy/paste in the SDK's text inputs and to and
  from LightOS. Light is doing it internally and asks for no duplicates or PRs.
- **Revert:** once the stock editor has it, go back to `LightTextInputEditor` in
  `TextEntryScreen` and drop `TextEditor`, `EditMenu` and the helpers after them.

## Answered upstream, not yet adopted

### Tool Manager — music from a computer, through a browser (SDK 0.2.0): to revisit

- **What it is:** a tool that declares `tool-manager-provider` in
  `lighttool.toml` shows up in LightOS's Tool Manager, a page the user opens in
  a computer's browser to move files to and from the phone
  (`light-sdk/docs/tool_manager/README.md`). Light's own example of a use is a
  music tool taking a user's library.
- **For Amp:** upload albums straight into Local Music, with no cable and no
  storage permission. Uploads land in the tool's own `files/shared/<path>`,
  and LightOS calls `onToolManagerDataUpdate()` on the entry point once they
  settle, where Amp would rescan.
- **Why not yet:** the Tool Manager's own README still calls support for SDK
  tools "soon", so check that LightOS lists Amp before building it. It also
  means a second root beside `Music/Amp` for `LocalLibrary` to scan.

### Detached audio — PR #148: ADOPTED, 31 Aug 2026

`newPlayer(playback = Detached)` + `capabilities = ["detached-audio"]`; the
plugin emits the service and its permissions into the tool manifest. Every one
of our player additions rides the `Player` interface, which the detached
`MediaController` implements. The background-audio section above has what
little remains ours. Still open: whether hardware volume keys route through
the SDK's session (the `LightActivity` pass-through spike stays until the
phone answers).

### Connectivity — PR #166: adopted, gap closed

`LightConnectivity` gives connected/Wi-Fi/metered and an observer. Amp took it
in 0.5.0 (as a verbatim backport, replacing an interface-polling heuristic);
since the 0.1.1 pin it is plain upstream code and the backport patch is gone.
The tool's `Connectivity.kt` remains only as a thin metered-ness wrapper for
the data-mode gates.

## Data modes — what "Wi-Fi Only" cannot be given today

Amp has a mode whose promise is that no cellular data is used. Everything the
tool's own code sends goes through one wall (`NetworkGate`) and is store-legal
as it stands. Three things it cannot reach from inside the sandbox, as of
`fb68d75`. None of them is a dependency: Amp works around each, and says below
what the workaround costs. **These are requests, not blockers.**

| Request | Why | What Amp does meanwhile |
|---|---|---|
| **A network policy for the player** — a tool-supplied predicate (or `DataSource.Factory`) consulted before the platform player opens or reads a remote source, for both the attached player and `LightAudioService`. | The player fetches with its own HTTP stack: the next item ahead of time, and a dropped stream again at once, on whatever network is then the default. A tool with a Wi-Fi-only setting has no say, and after the user backs out of the tool there is no tool code left running that could stop it. PR #127's deferred byte-reader API could carry this. | `light-sdk-patch/audio-network-policy.patch` (side-load builds only). A store build has no equivalent yet. |
| **Sockets bound to a network** — e.g. `LightConnectivity.unmeteredNetwork(): Network?`, or a `SocketFactory` bound to it. | The rule can only be asked about the phone's *default* network. If the default moves from Wi-Fi to cellular between the check and `connect()`, one TLS handshake (a few KB) leaves on cellular before the request is refused. `Network.bindSocket` closes that race; it needs `ConnectivityManager`, which a tool is not given. | Check before DNS, before the socket, and again on the wire; close every socket when the rule closes. No request or response byte leaks; one handshake can. |
| **The default-network callback in `observeNetworkStatus()`** | `registerNetworkCallback(request)` hears networks appear, change and vanish, but is not guaranteed to fire when the default moves from a weak Wi-Fi to cellular while both stay up — the one change that flips `isMetered`. | Re-read `currentStatus` every 3 s while playing or waiting, once a second while a download queue is held, and when the window regains focus. |
| **A resume hook for screens** — the counterpart of `onAppPause()`. | A process frozen in the background hears of a network change late or not at all; returning to the tool is the moment to look again. | Compose's `LocalWindowInfo.isWindowFocused`. |
| ~~**Cleartext to LAN servers and to loopback**~~ — **closed in SDK 0.2.0.** | The `cleartext-http` capability (light-sdk PR #223) writes `usesCleartextTraffic` into the generated manifest, so store builds reach `http://` servers too. | Declared in `tool/lighttool.toml`. The manifest-overlay line and `data/Cleartext.kt`, which explained the refusal, are gone. |

## Smaller additions

Already written as patches; see [SDK-PATCHES.md](SDK-PATCHES.md), which is the
authority. None were upstream as of `3df3c24` (0.1.1).

**Plan (2026-09-03, not yet done):** offer these upstream as **one PR** rather
than nine issues — they are written and working, so the PR is the ask. The
exception is **Room migrations**, which should go in on its own and as a *bug*:
without a migration path every schema change wipes the user's library cache and
download index, which is data loss rather than a missing convenience.

| Gap | Why |
|---|---|
| `popToRoot()` | A tab bar visible on nested screens has to unwind to the root. Without it, tapping a tab three levels deep only goes back one. |
| `onPlaybackError` | Media3 reports failures; the SDK swallowed them. A player that cannot report a failure cannot fall back to a downloaded copy. 0.1.1 grew an `error: StateFlow<LightAudioError?>` — real progress, but it maps the exception away and the fallback logic reads the raw `errorCode` (a bad HTTP status proves the server is *there*). The callback stays until the flow carries the code. |
| Room migrations | `buildDatabase` exposes no builder, so a tool cannot register a migration. We patched in `fallbackToDestructiveMigration`, which means every schema change wipes the user's cache and download index. |
| `replaceRange()` | Reordering by repeated `moveItem` rebuilds the session queue per move; a few hundred moves exhausts memory. |
| `isCurrentItemSeekable` | Whether the stream that arrived can be seeked. Guessing from the requested format is wrong when a server declines to transcode, and it fails silently. |
| `setHandleAudioBecomingNoisy(true)` | One line on the builder. Without it, unplugging headphones leaves music playing out of the speaker. Doing it by hand needs a `BroadcastReceiver`, which the sandbox blocks. |
| Playback state | The player exposes position, duration and `isPlaying`, but not the state behind them. A tool cannot tell a finished queue from a pause, so we infer it from position against duration. 0.1.1 added `availability` (connection lifecycle) and the `error` flow, but `STATE_ENDED` is still not surfaced. |
| `TextInputKeyboardCallback` | `LightEmbeddedLp3Keyboard` is public and usable on a tool's own screen, but the callback that turns keys into edits is `internal`. We copied ~60 lines of it, including surrogate-aware backspace, to put a keyboard under our search results. |
| Splash icon centring | The loading glyph sits ~88px above centre on a 1240px panel; the artwork is off-centre inside its own viewport. |

## Blocked above the API

- **Physical buttons.** The side buttons and dimmer wheel are behind a LightOS
  token trust-gate. A side-loaded tool cannot bind them and no SDK patch changes
  that.
- **The stock Music tool's library.** Not the storage — the metadata. Corrected
  2026-09-03, having actually looked: the files sit in
  `/storage/emulated/0/Download/Persisted/Music/` (shared storage, alongside a
  `Podcasts/` sibling) and *are* indexed in MediaStore, so reading them is
  probably possible today. What is missing is anything to show. The filenames
  are UUIDs, the files carry no real tags (only ffmpeg's `TSSE`/`TXXX`
  leftovers — no `TIT2`, `TPE1` or `TALB`), and MediaStore holds
  `title=<the UUID>, artist=<unknown>, album=Music`. The real titles live only
  in `com.lightos`'s private database. So a tool could list six tracks named
  after their UUIDs, which is not a library. Amp reads its own folder, which is
  why local files go in `Music/Amp`. Worth re-checking after the Music tool
  rework: if it starts writing real tags, this closes by itself.
