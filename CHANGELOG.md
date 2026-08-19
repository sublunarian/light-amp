# Changelog

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
