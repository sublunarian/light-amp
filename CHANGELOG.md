# Changelog

## Unreleased

### Changed

- While typing, hold the text to clear it, as in Notes.
- Passwords show while you type them and stay hidden after.
- Groundwork toward getting Amp into Light's Tool Library.

### Fixed

- Reopening Amp after a long pause could leave the play button doing nothing.
- Leaving Amp just after starting a song could crash it.

## 0.7.0

### Added

- Batch editing: select several songs in a playlist or the queue and move
  them together. Thanks to @wsturgiss.
- Album covers are saved while you're on Wi-Fi, so they show up on cellular
  without using data.

### Changed

- Switching between Wi-Fi and cellular is faster and more reliable: music
  keeps playing, and the library and queue update right away.
- Songs start faster on slow connections.

### Fixed

- Wi-Fi Only could still use cellular data in some cases.
- Several playlist and download bugs.

## 0.6.1

### Fixed

- Switching source could leave the library looking empty for a long while,
  or narrowed by a filter chosen on the previous server. The genre, composer
  and liked filters now clear on a switch, a narrowed page says so in its
  title, and a set filter can always be cleared from More.
- Large Plex playlists were fetched as one response the phone couldn't hold,
  which failed in the background on every download check. They are fetched a
  page at a time now, and a playlist that won't answer is left alone for a
  while rather than asked again immediately.
- Download checks wait a few seconds after a source switch, so the screen
  refills before the background work starts.

## 0.6.0

### Added

- Cast to Plex players from the Output page when the active source is Plex.
  The player plays the queue itself; Amp steers and mirrors, and takes
  playback back where the player left it.
- Replay Gain, under Settings → Playback, on by default. Navidrome, Jellyfin
  and the phone's own files; Plex has nothing to read yet. Only ever turns a
  track down.

### Changed

- Background audio uses the SDK's own detached audio service. A paused queue
  winds down after 15 minutes.
- Downloads run for every source at once, whichever source you're browsing.
  Each server's Auto-Download setting applies to that server, and switching
  source no longer clears the queue.
- The keyboard follows the phone's keyboard settings: swipe typing, key
  animation. No emoji key.
- SDK updated to 0.1.1. Audio-focus handling (ducking, resuming after a call)
  now comes from Media3.

### Fixed

- Likes, ratings, lyrics, timelines and now-playing reports went out on
  cellular in Wi-Fi Only. They wait for Wi-Fi now, like plays.
- Streamed tracks around the edges: a restored track read 0:00, Previous
  couldn't reach the previous track, a streamed queue didn't park at its start,
  and repeat could stop playback.
- Switching source could leave downloads asking the new server for the old
  one's tracks, and retrying forever.
- Downloaded MP3s from Plex showed the wrong length, played on in silence past
  the end and seeked to the wrong place. Existing downloads are repaired at
  the next launch.
- A Plex library was re-fetched in full on every sync.
- Lyrics were never stored with a downloaded Plex track.
- Every playlist's contents were fetched twice at launch.
- The Downloads page sat still while songs were downloading.
- Downloading an album fetched its cover with artwork turned off.
- The Output page searched for players with Wi-Fi off.
- Stopping the queue while casting to a Plex player left it subscribed.

## 0.5.0

### Fixed

- Data modes now hold everywhere. Downloads and cover fetches never run on
  metered data (cellular, or a phone-hotspot Wi-Fi).
- The network check reads the connection the phone is actually using, through
  the SDK's new ConnectivityManager hooks (light-sdk #163). A Wi-Fi that had
  lost internet used to still count as Wi-Fi while every byte rode cellular.
- The whole UI now sizes itself from the panel's real width, the way the
  phone's own tools do. Changing Android's "smallest width" (density) setting
  used to shrink every control and font while other apps stayed put; now Amp
  renders the same pixels at any setting, and scales properly on any future
  panel.

### Changed

- Low Data is now the default for new installs. It was Wi-Fi Only — which,
  now that Wi-Fi Only really means no network, would have left a first
  sign-in over cellular with nothing synced and no visible reason.


## 0.4.1

### Fixed

- Playing to the end of a queue closed the app, and reopening put you back at
  the same spot to do it again. Present since 0.3.0.

## 0.4.0

The first launch trims the artwork cache to 200 MB in the background; covers
come back as you browse.

### Added

- Start Song Radio, in every song's menu, on Navidrome, Plex and Jellyfin.
  Started from the playing song, it keeps the song and replaces what follows.
- Save Queue as Playlist, in the + menu.
- Hold the top-right button on any page, or on the player, to open Settings.
- Tap Random again in a sort menu for a new shuffle. The sort page says what a
  second tap does.
- Delete one server's downloads from the Offline page.
- An artist's popular songs are kept for a week and shown offline.

### Changed

- Settings is five pages: Appearance, Sources, Data, Offline and About.
  Streaming quality is under Data; auto-download and download quality are under
  Offline. A server's page keeps Connection, Player Name, Shown on Sources, Sync
  Now and Log Out.
- "Offline Mode" is now "Auto-Download".
- Album pages put track numbers in a gutter that fits three digits, and show the
  artist line only where the tracks' artists differ. The corner button opens the
  album's own menu, not a sort menu. Playlists likewise.
- The player's header is the same bar as everywhere else.
- Covers are fetched at 1080px.
- The "Plays" sort is "Frequently Played". Rating is offered only where the
  server can rate.
- Hide Artist Photos is on by default.

### Fixed

- Local Music said it had no access on phones where the permission was granted,
  and Sync Now led back to Allow Music Access.
- Switching from a cast back to This Device started the song over. It now
  picks up where the speaker was.
- While casting, dragging to the end of a song waited, then restarted it. It
  goes to the next song.
- Seeking while casting replayed the song from the top before jumping. It seeks
  in place.
- The clock flashed the previous song's time on Next while casting.
- A source switched during a sync could write one server's albums into
  another's library, where they showed in every library. Fixed, and the stray
  rows are removed on the next sync.
- Everything and Favorites never auto-downloaded playlist songs.
- Delete All Downloads emptied only the active source.
- The artwork cache grew without limit — 3 GB on one phone — keeping a copy of
  each cover per song. One per album now, 200 MB at most; the covers of
  downloaded albums are always kept.

### Removed

- The Monochrome Artwork setting. Color follows the artwork switch.

## 0.3.1

### Fixed

- Long tracks took minutes to start. A 29-minute track needed 68 seconds; the
  same 124-minute one now starts in under a second.
- Library tabs lost their place when you switched away and back.

### Added

- Album track rows show the length under the performer.

### Changed

- Full-screen artwork opens with the controls out of the way when you go to it;
  opening the player still brings them back.
- No artist pictures on the phone's own library, which has none to show.
- The Source row is hidden when there is one source with one library.

## 0.3.0

**Needs one online sync after updating.** Downloads are safe; the Downloads page
looks empty until the sync finishes.

### Fixed

- Artists listed track performers, so every "feat." guest became its own artist.
  Now album artists.
- Switching library while offline emptied the library.
- Playlists, Artists and Albums did nothing when pressed from an album page.
- The queue ended parked on the last song. It now returns to the first, stopped.
- Each source was measured against the size limit separately.
- The size limit now counts what is already downloaded.

### Added

- Search on the page you're in, results narrowing as you type.
- Genre and Composer filters on Albums and Songs.
- Offline browsing — every list shows what you can play.
- The Downloads page lists every library and source. Read-only.
- Liked is a single switch.
- The queue header says "3 of 12".
- Album pages show who plays on each song, not its length.
- Pressing the current tab returns to the top of it.

### Changed

- The expanded tab bar is the default again. The setting is now "Simplified
  Library View"; existing installs keep their layout.
- One storage limit for the whole app, in Settings.
- The bottom bar's fifth button is the player; search moved to the header.
- Every library stays cached, so switching is instant and works offline.

### Removed

- The Compilations page.
- The Genres and Composers pages, now filters.
- The More page.
- Each source's separate Downloads page.
- The per-source download library setting, and the Include Lyrics toggle.
