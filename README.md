# Amp

**A music player** for the Light Phone III.

Plays your own library — a Subsonic server, Plex, Jellyfin or files on the phone.

<p align="center">
  <img src="docs/screenshots/albums.png" width="24%" alt="All albums, with the A–Z index">
  <img src="docs/screenshots/now-playing.png" width="24%" alt="Now playing">
  <img src="docs/screenshots/lyrics.png" width="24%" alt="Synced lyrics">
  <img src="docs/screenshots/artist.png" width="24%" alt="An artist's albums">
</p>

> Side-load only for now. The tool store isn't open yet.
>
> For advanced users at this stage — installing means USB debugging and `adb`,
> and things will break.

## Features

- Subsonic (Navidrome, Airsonic, Gonic), Plex, Jellyfin and/or local files on the phone.
- Bandcamp [speaks Subsonic](https://blog.bandcamp.com/2026/07/16/discover-improvements-and-subsonic-implementation/)
  now, so you can use Amp without hosting anything.
- Offline downloads, by album, track, or everything you've liked.
- Data Mode: choose what happens off Wi-Fi. Nothing is silently downgraded.
  **Defaults to WiFi Only**, so on cellular it plays your downloads rather than
  streaming — change it in Settings if you'd rather it streamed.
- Synced lyrics.
- Play counts, ratings, likes and playlist edits sync back to the server.
- Cast to a DLNA speaker or receiver, or — on a Plex source — to another Plex
  player such as the Apple TV app. A Plex player fetches the music from your
  server itself, so the quality is settled between those two: Amp's streaming
  setting doesn't apply, and lossless depends on the player's own quality
  setting. The player also needs "Remote Control: Advertise as Player" turned
  on.
- Artwork can be turned off completely.

## Installing

USB debugging needs to be on: **Settings → Developer options → Allow USB
debugging**. Then take the APK from [Releases](../../releases) and either drop
it on [Light Phone Manager](https://github.com/greghare/light-phone-manager), or:

```bash
adb install -r amp-*.apk
```

## Music on the phone

Amp reads `Music/Amp`, and nothing else.

```bash
adb push ~/Music/some-album "/sdcard/Music/Amp/"
```

Sub-folders are fine. Names come from the tags rather than the folders, and
artwork has to be embedded in the files — a `cover.jpg` next to them won't show.

Playlists live in `Music/Amp/Playlists` as `.m3u8`, so one you make on the phone
opens anywhere else, and one you drop in that folder shows up in Amp.

## What isn't in the SDK yet

Four things sit outside the official SDK. **Background audio** and **background
downloads** are the two that stop this being a plain SDK tool. **Colour** and
**DLNA casting** are extras that would come out for a store build.

What each stands in for, and what would replace it, is in
[SDK gaps](tool/docs/SDK-GAPS.md). The SDK changes themselves are listed with
revert steps in [SDK patches](tool/docs/SDK-PATCHES.md).

Colour is the only one you have to do anything about. It's off by default, and
switching the phone's greyscale filter needs a one-time grant:

```bash
adb shell pm grant com.sublunar.amp android.permission.WRITE_SECURE_SETTINGS
```

Colour then simply follows the artwork setting — artwork on means colour on.

## Building

Light's SDK comes in as the `light-sdk` submodule; Amp is the `tool/` module
built against it, which is the shape a LightOS tool takes. Every change Amp
makes to the SDK itself is a patch file in [`light-sdk-patch/`](light-sdk-patch/),
and the build applies the set to the pristine submodule for you.

```bash
git submodule update --init
./gradlew :tool:assembleRelease
```

## Licence

MIT. The app is © Amp contributors; Light's SDK, © The Light Phone, comes in
as a submodule, with our changes carried as patches on top. See
[LICENSE](LICENSE).
