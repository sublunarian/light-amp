# How it fits together

About 17,000 lines in `com.sublunar.amp`, in five parts. If you're looking for
where something lives, start here.

```
App.kt                 wiring: what owns what, and what happens on a source switch
ToolEntryPoint.kt      the SDK's way in
BackgroundSync.kt      the periodic library refresh

data/                  servers, storage, settings — everything non-visual
  db/                  Room: one database file per source

playback/              the player, the queue, casting
art/                   artwork fetching, decoding and caching
ui/components/         the shared kit — rows, headers, scroll furniture
ui/screens/            one file per screen, plus the tabbed shell
```

## The spine

**`App`** is the composition root and the only global. It owns the settings, the
repository, the downloader, the player and the artwork loader, and it holds the
active source. It is also where a **source switch** is handled, and that is worth
reading before anything else: switching swaps the database under every flow at
once, and a surprising amount of state has to be dropped with it — the playback
queue, playlists, popular songs, the search index, which tabs were showing their
liked list. Anything keyed by name rather than by source will answer for the
wrong server if you forget it.

The one thing a switch does *not* drop is the download queue. Selecting a source
is a browsing choice, not a download scope: every queued track names its own
source, and `Downloader` resolves the client, the tables, the folder and the
format per track from that — so a Plex library set to download everything keeps
arriving while you browse Navidrome or the phone's own files. `App` keeps one
client per source for that reason, closed only when the source is removed, and
the top-up reads every source's tables rather than the repository's lists.
`DownloadQueue` is the pure part — two lanes, per-source keys, where a failure
goes back to — and has tests.

**`LibraryRepository`** is the library. Room-backed lists come out as eagerly
shared `StateFlow`s so a screen never renders an empty list on its way to a full
one; the sync pulls albums, then each album's tracks, and reconciles favourites.
Playlists are the exception — they have no table and live in memory, which is why
they need clearing by hand on a switch.

**`MusicServer`** is the seam between the app and whatever it is talking to.
`SubsonicClient` and `PlexClient` implement it; the phone's own files are a third
source with no client at all. Everything above this line works in the app's own
models (`Track`, `Album`, `Playlist`).

Two rules make that seam work:

- **A server declares what it can do.** Methods only some backends support have
  no-op defaults, `MusicSource` carries capability flags the UI reads, and
  `MusicServer.streamFormats` says which formats a server will *actually*
  deliver. A feature a server can't support is absent, not present and broken.
- **No silent substitution.** If a chosen format can't be served, the app doesn't
  quietly send something else — it doesn't offer the choice in the first place.

**`PlaybackController`** owns the player, the queue, position and duration, and
the cast session. The DLNA section is fenced off behind its own banner comment
because it is a workaround due for removal — see [SDK-GAPS.md](SDK-GAPS.md).

**`ui/screens`** is one file per screen. `LibraryShell` is the tabbed root;
`BootScreen` hosts it and decides what to show while the library loads.
`LibraryNav` holds the navigation state screens share, because the tab bar is
visible on nested pages and a screen three levels deep still has to be able to
select a tab.

## Things that will bite you

Written down because each one cost a debugging session.

**The SDK composes only the top of the back stack.** A screen underneath is
disposed, not hidden. That is why scroll positions are saved in `ScrollAnchors`
rather than in `remember`, and why "is the shell the thing the user is looking
at?" is a question `LibraryNav` has to answer explicitly.

**ExoPlayer only tolerates the main thread.** `PlaybackController.stop()` places
itself there because it is called from a background collector as well as from
the UI. `restoreState` carries the same guard.

**Full-table `Flow` queries need `@Transaction`.** A result that size spans
several cursor-window fills, each its own snapshot; a sync writing between two of
them throws `Couldn't read row N from CursorWindow` and takes the app down.

**Settings read straight from the store arrive late.** `collectAsState(initial =
…)` means the first frame uses the placeholder and then re-renders — visible as a
list sorting itself twice. Anything on a hot path is held in an eagerly shared
`StateFlow` on `App` instead.

**Two writes are visible between each other.** Clearing likes and re-applying
them as separate statements leaves a moment where nothing is liked, and every
observer sees it. Swap sets inside one `@Transaction`.

**A `graphicsLayer`'s `clip` applies before its transform.** Clipping a zooming
image that way scales the clip rectangle too. Clip on an untransformed parent.

**Every socket goes through `NetworkGate`.** Wi-Fi Only is a wall at the
transport, not a set of checks: HTTP clients are built with
`NetworkGate.httpClient`, the player asks the SDK's `LightAudioNetworkPolicy`,
and the raw paths ask `NetworkGate.check()`. A bare `HttpClient(OkHttp)` or
`URL.openConnection()` anywhere else is a hole in that wall — the courtesy
checks (`metadataAllowed`, `heavyDataAllowed`) only save work, they stop nothing.

**Conditional composable calls stop subscribing.** A `collectAsState` behind an
`&&` or inside an `if` silently stops triggering recomposition. Read
unconditionally, branch on the result.

## Units

Every dimension is in the LP3's own physical pixels: a 1080-wide canvas, which
is Light's 27-unit grid at 40px a unit. `px(n)` and `pxSp(n)` resolve the canvas
against the window's real width (`Scale`, in `ui/Sizing.kt`) and round to whole
panel pixels — a design pixel is a fraction of the panel, never a dp. Absolute
dp is how 0.4.x broke: Android's "smallest width" setting lowers the density,
every dp becomes fewer pixels, and the app shrank on a panel that hadn't moved,
while the SDK's own width-fraction components didn't. On the LP3 the factor is
exactly 1, so the numbers in the code are still what a ruler finds on the
screen. Sizes were measured on the panel rather than converted from a design
made for another phone — where a comment quotes a measurement, it came off a
screenshot.

Type sits on Light's own scale (`LightType`, measured off the phone's stock
tools): Detail 41, Fine 52, Copy 62, Heading 79. Menu rows follow the shape of
LightOS's own — one line for a verb, a Detail label over a large value for a
setting, text on the 80px axis — but set a step down its scale, at Copy: the
stock menus' Heading was tried and read too loud on a page of rows. The lists
keep the Music list's 72px axis. The lists sit a notch under Light's own
(`ROW_*_PX` in `ui/components/Rows.kt`), judged on the device against them.

## Comments

The code explains *why*, not *what*. A comment beside something
counter-intuitive is usually the record of what happened when it was the obvious
way instead. If you change such a line, the comment is the review — if it no
longer describes the code, one of the two is wrong.
