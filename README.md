# Amp

**(A)nother (M)usic (P)layer**, for the Light Phone III.

Plays your own library — a Subsonic server, Plex, or files on the phone — and is
built on Light's SDK, so it looks like it belongs there.

<p align="center">
  <img src="docs/screenshots/now-playing.png" width="24%" alt="Now playing">
  <img src="docs/screenshots/lyrics.png" width="24%" alt="Synced lyrics">
  <img src="docs/screenshots/artist.png" width="24%" alt="An artist's albums">
  <img src="docs/screenshots/albums.png" width="24%" alt="All albums, with the A–Z index">
</p>

<p align="center"><em>Colour is optional and off by default — the Light Phone III
is greyscale unless you turn its filter off. See <a href="#what-isnt-in-the-sdk-yet">below</a>.</em></p>

> Side-load only for now. The tool store isn't open yet.
>
> For advanced users at this stage — installing means USB debugging and `adb`,
> and things will break.

## Features

- Subsonic (Navidrome, Airsonic, Gonic), Plex, and the phone's own files. Add as
  many as you like and switch between them; each keeps its own downloads,
  settings and cache.
- Bandcamp works too. It [speaks Subsonic](https://blog.bandcamp.com/2026/07/16/discover-improvements-and-subsonic-implementation/)
  as of July 2026, so you can use Amp without hosting anything.
- Downloads for offline, by album, track, or "keep everything I've liked", with
  a storage limit.
- Streaming quality set separately for wifi and cellular. I stream FLAC at home
  and keep MP3s on the phone for everywhere else.
- Synced lyrics, from your server or [lrclib.net](https://lrclib.net).
- Play counts, ratings, likes and playlist edits sync back to the server.
- Cast to a DLNA speaker or receiver.
- Artwork can be turned off completely, if you'd rather read a list of words.

I've been using it as my only music player for a week, on a library of about
8,000 tracks with 50GB downloaded.

## Installing

Turn on USB debugging first: **Settings → Developer options → Allow USB
debugging**.

Then get the APK from [Releases](../../releases) and either drop it on
[Light Phone Manager](https://github.com/greghare/light-phone-manager), or:

```bash
adb install -r amp.apk
```

Open it and add a source. Subsonic wants an address, username and password —
cleartext HTTP is allowed, so a server on your LAN works. Plex signs in with a
code at plex.tv/link, so nothing gets typed on the phone.

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

Amp mostly sticks to the official SDK. Two things it can't do through it, and
both are why it isn't a plain SDK tool yet:

- **Background audio.** Music stops when you leave the app unless something owns
  a media service, and a tool can't start one. Amp ships one in the SDK layer.
- **Background downloads.** A backgrounded transfer gets throttled about ninefold
  on this phone, which is the difference between an album arriving and not.

Two more are off-SDK because I wanted them, and would come out for a store build:

- **Colour.** The greyscale is a device-wide filter applied after everything is
  drawn, so no app can paint around it. Amp can switch it off while it's in front
  and put it back when you leave, but that needs a permission you grant once:

  ```bash
  adb shell pm grant com.sublunar.amp android.permission.WRITE_SECURE_SETTINGS
  ```

  Then turn off **Settings → Monochrome Artwork**. Without the grant the
  permission is inert and the toggle does nothing.
- **Casting.** There's no output-routing API, so the UPnP discovery is written by
  hand.

All four, and what would replace them, are in [SDK gaps](tool/docs/SDK-GAPS.md).
The SDK changes themselves are listed with revert steps in
[SDK patches](tool/docs/SDK-PATCHES.md).

## Building

This repo is [lightphone/light-sdk](https://github.com/lightphone/light-sdk) with
the app in its `tool/` module, which is the shape a LightOS tool takes.

```bash
./gradlew :tool:assembleRelease
```

Set `serverPackage` in `tool/lighttool.toml` to `com.thelightphone.sdk.emulator`
if you're running the emulator instead of a phone.

## Licence

The app, everything under `tool/`, is MIT © Amp contributors. The rest is Light's
SDK, MIT © The Light Phone, carried here with our changes on top so it builds in
one command. See [LICENSE](LICENSE).
