# How it fits together

About 38,000 lines in `com.sublunar.amp`. If you're looking for where something
lives, start here.

```
App.kt                 wiring: what owns what, and what happens on a source switch
ToolEntryPoint.kt      the SDK's way in
BackgroundSync.kt      the periodic library refresh

source/                one folder per kind of library, and what they share
  SourceProvider.kt    what a kind of source fills in (SourceUi.kt: the part screens read)
  Providers.kt         the registry: one line per kind
  PhoneLibrary.kt      what the app asks of music that is files on the phone
  ServerHttp.kt        each server client's two HTTP clients, its JSON, URL and query building
  SignInOutcome.kt, PendingServer.kt, ServerAddress.kt   shared sign-in pieces
  subsonic/            Navidrome, gonic, Airsonic, Bandcamp…: client, wire types, sign-in, form
  plex/                client, wire types, plex.tv account, link screens
    companion/         Plex Companion: casting to Plex players, an output the provider offers
  jellyfin/            client, wire types, sign-in, link screen
  local/               the phone's own files: LocalLibrary, m3u playlists, ReplayGain tags

data/                  the app's own models, settings, the library, downloads, the network wall
  db/                  Room: one database file per source

playback/              this phone's own playback: the controller, and what it hands off
  output/              the slot every control talks to while the sound is elsewhere
    dlna/              DLNA speakers: discovery, the SOAP calls, the cast
art/                   artwork fetching, decoding and caching; the cover sync
ui/components/         the shared kit — rows, headers, scroll furniture
ui/screens/            one file per screen, plus the tabbed shell
```

Each kind's wire types and their mappers to the app's models sit together, in
its `*Dtos.kt`.

## The spine

**`App`** is the composition root and the only global. It owns the settings, the
library, the downloader, the player and the artwork loader, and it holds the
active source. It is also where a **source switch** is handled, and that is worth
reading before anything else: switching swaps the database under every flow at
once, and a surprising amount of state has to be dropped with it — the playback
queue, playlists, popular songs, the search index, which tabs were showing their
liked list. Anything keyed by name rather than by source will answer for the
wrong server if you forget it. What a screen does to a source in several steps
is on `App` too, in the order that matters: `removeSource` stops playback first
when it plays from that source, and `useSource` writes the library and the
active source before the screens unwind, then switches library.

The one thing a switch does *not* drop is the download queue. Selecting a source
is a browsing choice, not a download scope: every queued track names its own
source, and `Downloader` resolves the client, the tables, the folder and the
format per track from that — so a Plex library set to download everything keeps
arriving while you browse Navidrome or the phone's own files. `App` keeps one
client per source for that reason, closed only when the source is removed, and
the top-up (`Downloader.topUp`) reads every source's tables rather than the
library's lists. A source whose downloads wait has one `Downloader.Hold` —
refused by its server, or parked while it doesn't answer — lifted when its
record changes; one download comes to a `DownloadStore.Fetched` (saved,
refused, failed), so each failure carries its own words. `DownloadQueue` is the
pure part — two lanes, per-source keys, where a failure goes back to — and has
tests.

**The library** is three siblings App builds over the active source's database.
`LibraryRepository` has the lists, reads, likes and search: Room-backed lists
come out as eagerly shared `StateFlow`s so a screen never renders an empty list
on its way to a full one, and the chosen library's songs are one `Indexed` read
whose maps (by id, the badge counts) are worked out on first use and let go with
it. `LibrarySync` keeps the cache in step with the server: the one `SyncState`
the screens show, `runSync` as an outline of named steps (albums, then the songs
of the albums that need them, then favourites), Sync Now's scan, and the repoint
of a server that moved address. Its policy is pure and tested, in files of its
own: `SongRereads.kt` (which albums' songs to read again, and whether songs
changed) and `SyncFailure.kt` (turned away or not there, and the words for it).
`LibraryPlaylists` has the playlists, which songs each holds and the writes. Its
list has no table — it is cached in the settings and held in memory — which is
why it needs clearing by hand on a switch.

**`MusicServer`** is the seam between the app and whatever it is talking to.
`SubsonicClient`, `PlexClient` and `JellyfinClient` implement it, each in its
folder under `source/`; the phone's own files (`source/local`) have no client.
Everything above this line works in the app's own models (`Track`, `Album`,
`Playlist`). A like is one `setLiked(target, id, liked)`. What came of a write
is a `WriteResult`, in `data/ServerException.kt` beside the failure types and
the one wording for a server that is down: a server that never answered is
sent the write again later, one that answered no is not. `PendingActions.attempt`
is the one way a write that may have to wait is sent — likes, ratings and plays.
Signing in is one shape for every server: `inspectSubsonicServer`
(`SubsonicSignIn.kt`), `JellyfinSignIn.signIn` and `inspectPlexServer` each
answer a `SignInOutcome`.

Two rules make that seam work:

- **A server declares what it can do.** Methods only some backends support have
  no-op defaults; each kind's provider says what it can do, which `MusicSource`
  passes on as the capability flags the UI reads, and lists the formats its
  servers will *actually* deliver (`streamFormats`, read through
  `MusicSource.streamFormats`). A feature a server can't support is absent, not
  present and broken.
- **No silent substitution.** If a chosen format can't be served, the app doesn't
  quietly send something else — it doesn't offer the choice in the first place.

**`SourceProvider`** is what a kind of source fills in: its formats, what it can
do, how to build its client, how it is added, its Connection row, Log Out's word
to the server. `Providers` lists one per kind, and shared code asks a source's
provider (`source.provider`, or `Providers.of(kind)`) rather than testing its
kind. The phone's own files have no server, so their provider hands out a
`PhoneLibrary` instead (`LocalLibrary` is that library: the scan, the m3u
playlists, the permission), and that is the only way the app reaches them.
Their track ids name the file (`PhoneFiles`, in `data/`), because playback,
covers and downloads have to tell a phone file from a server's track whichever
source is being browsed.

**`PlaybackController`** owns the player, the queue, position and duration,
and keeps that state on Main. Amp keeps the whole queue; the player holds a
window of it (`playback/QueueWindow.kt`: at most `PLAYER_WINDOW` rows,
`WINDOW_BEHIND` of them before the playing one), which slides with `replaceRange` as
playback nears an edge (`keepWindowAround`) and is rebuilt around a jump outside
it. The queue index is the window's start plus the player's. An edit applies to
the whole queue, and to the player only where it falls in the window.
Repeat-queue loops in the player only when the window is the whole queue;
otherwise the controller wraps, rebuilding at the start. The queue saved for the
next launch is a slice of it centred on the playing track (`savedRows`, at most
`AppSettings.MAX_SAVED_QUEUE` ids), and the saved index points into that slice.
Beside the controller: `ServerReports` (what the server is told — sessions, the
timeline, now-playing, plays, the queue saved on the server), `TrackSources`
(file, download or stream, in which format from which address, and what
failed), `QueueMath.kt` (the pure queue arithmetic) and `PlayFailure.kt` (a
player failure sorted by what happens next).

Plays follow Last.fm's rule and every major client's: a play begins at each
track start (a repeat, a song queued twice, a restart), now-playing goes out
once per play, and the play counts once half the song or four minutes has been
heard, sent or queued through `PendingActions.attempt`. A song that ends into
itself starts a new play only while playback is under way: a one-song queue
that runs out is rewound and waits for a play.

While the sound is somewhere else, every control goes to the current **output**
instead (`playback/output/Output.kt`); none means this phone. An `Output` steers
one far end and keeps the controller's state true through `OutputHost`, so the
screens read the same flows whoever is playing. Its `OutputFamily` carries the
constants (log tag, whether the far end reports its own plays, whether it needs
the LAN), so nothing builds an output to ask. DLNA is one, in
`playback/output/dlna` — discovery, the SOAP calls (`DlnaCast`), the cast
(`DlnaOutput`) and its poll's pure decision (`CastWatch`) — listed in
`Outputs`, Amp's own code rather than an SDK patch. A cast remembers its format
choice per renderer and format, and per track for Original, which is each file's
own container (`formatChoiceKey`). Plex Companion is the other, in
`source/plex/companion` — discovery, the listener, the cast and its calls
(`PlexCompanionCalls.kt`, extensions on `PlexClient`, which holds the token and
the lock they need) — offered by the Plex provider
(`SourceProvider.outputFamilies`), so only a Plex source lists its players. The
Output page lists `OutputFamilies` — the source's and the phone's — without
naming any of them.

While an output has the sound, the phone's own player plays a **stand-in**
(`playback/StandIn.kt`): one silent MP3, named and timed like the output's song
and kept at its place and play state. The media session belongs to that player,
so a launcher's controls, Bluetooth buttons and LightOS see what the output is
doing, and a press that reaches the stand-in is passed on to the output. Playing,
it also keeps the media service in the foreground, so Android doesn't freeze Amp
while it casts with its screen closed. The queue stays the controller's alone
until the sound comes back, when it goes back into the player.

**`ui/screens`** is one file per screen, named after what it holds
(`ArtistScreens.kt`, `PlaylistDetailScreen.kt`, `AlbumArtworkScreen.kt`).
`LibraryShell` is the tabbed root, drawn by `BootScreen` (as
`SimpleLightScreen<*>.LibraryShell()`), which decides what to show while the
library loads; its tab bar is `Navbar.kt` and its search `LibrarySearch.kt`.
`LibraryNav` holds the navigation state screens share, because
the tab bar is visible on nested pages and a screen three levels deep still has
to be able to select a tab; opening search or a new playlist goes through
`Nav.kt` too. Every pushed page is an `AmpScreen`, which sends the volume keys
to the speaker while casting (`BootScreen` does its own). The player keeps its
queue and lyrics in `NowPlayingQueue.kt` and `NowPlayingLyrics.kt`; every
format choice is one `FormatPickerScreen` (its `FormatSlot`: Wi-Fi, cellular,
download), and every sort menu one `SortScreen`.

## Adding a kind of source

A folder under `source/` with its client (implements `MusicServer`), its
provider (implements `SourceProvider`) and its sign-in screens; one
`SourceKind` constant, which is the name its sources are stored under, so it is
never renamed; and one line in `Providers`. Nothing outside the folder may
import it. A kind whose server can steer players of its own offers them as an
output family, from its provider, as Plex does.

## Things that will bite you

Written down because each one cost a debugging session.

**The SDK composes only the top of the back stack.** A screen underneath is
disposed, not hidden. That is why scroll positions are saved in `ScrollAnchors`
rather than in `remember`, and why "is the shell the thing the user is looking
at?" is a question `LibraryNav` has to answer explicitly.

**ExoPlayer only tolerates the main thread.** `PlaybackController.stop()` places
itself there because it is called from a background collector as well as from
the UI. `restoreState` carries the same guard. The controller's own state lives
on Main too, but its collectors are posted rather than immediate: the SDK sets
its flows in the middle of its own commands, and an immediate collector would
run inside them. The outputs still call the host from their own threads.

**Full-table `Flow` queries need `@Transaction`.** A result that size spans
several cursor-window fills, each its own snapshot; a sync writing between two of
them throws `Couldn't read row N from CursorWindow` and takes the app down.

**Settings read straight from the store arrive late.** `collectAsState(initial =
…)` means the first frame uses the placeholder and then re-renders — visible as a
list sorting itself twice. So each setting in `AppSettings` is a `Pref` (a
read-only one a `Stored`): a Flow, read from the store, for code that has just
written, and an eager `state` that starts from what was stored at launch, for
screens. A screen reads `.state`. `App` holds only what several settings work
out together (`hideArtwork`, `albumGrid`, `artistAlbumGrid`, `hideArtistImages`,
the tag filters).

**Two writes are visible between each other.** Clearing likes and re-applying
them as separate statements leaves a moment where nothing is liked, and every
observer sees it. Swap sets inside one `@Transaction`.

**A `graphicsLayer`'s `clip` applies before its transform.** Clipping a zooming
image that way scales the clip rectangle too. Clip on an untransformed parent.

**What the connection allows is one value: `App.rules`.** The link, its price,
the data mode and whether the server answers are combined into a `LinkRules`,
and the library filter, the queue's dimmed rows, playback, downloads and artwork
all read that. Anything that keeps its own copy of part of it — a metered flag,
a "reachable" latch — will disagree with the rest after a network switch for as
long as its own update takes. A failure is a *suspicion*: `Reachability` settles
it with one ping, at once and on every network change, never by waiting for a
sync; `askServer` is the one verdict on a ping, for the browsed server and for a
parked download server alike. Every reaction to the rules moving is in one
collector in `App.boot`, in one order (`NetworkGate.rulesMoved`, then
`App.rulesMoved`, which passes the edges on to their owners), and a change of
link has one collector of its own: drop every connection, ask the server again,
then the parked download servers.

**The player never talks to the music server; `StreamProxy` does.** The
platform player fetches with its own HTTP stack, outside `NetworkGate`, preloads
the next track, reconnects on whatever network is current, and keeps playing
after the tool's screens are gone. So it is handed `http://127.0.0.1:port/s/…`
addresses, and the proxy — in the app's process scope — fetches the real bytes
through the gated client, deciding per request under the rules in force at that
moment. That is why a network or data-mode change never needs the player's
queue edited. Plain HTTP, which the SDK's `cleartext-http` capability permits
in every build. It proves itself at start by fetching from itself; if that
fails, `wrap()` returns the server's own URL and the About page says why.
Anything that hands the player a URL must go through `App.streamProxy.wrap()`,
which `TrackSources.playerUrl` does for every stream.
Between server and player sits a bounded read-ahead pipe fed by its own thread;
a link change under a stream is re-fetched and spliced behind that slack — by
range where the server honours ranges, otherwise re-encoded from the top with
the delivered bytes skipped and checked head and tail — so the player never
sees the break. It matters because media3 treats a length-less transcode as
live and, shown an error, restarts the song from the top once its buffer runs
dry.

**Background work steps aside for a starting stream.** `PlaybackController.buffering`
is true from a stream being asked for until its first sound; the sync, playlist
priming and the downloader call `App.yieldToPlayback()` between requests. The
reachability ping and the stream decision use each client's `quick` HTTP client
(`ServerHttp`), so they never queue behind bulk requests.

**The artwork cache is a copy of the libraries, not a history of them.** One
panel-sized file per cover in `files/artwork`, named by a hash of source and
cover id — so a file can only be found through an id, and whoever removes an id
has to name it. Three rules keep it honest. A cover goes when the library lets
go of it: a sync names the ids its database held before and doesn't now
(`LibrarySync`'s `onCoversDropped` → `ArtworkLoader.drop`), which covers a
changed sleeve as well as a deleted album, since Navidrome's and Plex's cover
ids carry the date the art last changed. A file no library names, unread for a
month, is deleted at launch (`expireUnclaimed`) — the month is for covers that
are legitimately nobody's, like a playlist track from a library never synced.
And the whole is held to a budget, least recently read first, never at the
expense of a downloaded album's sleeve (`trimToBudget`). Filling it is
`CoverUpkeep.sync` (`art/CoverUpkeep.kt`, which also works out what the budget
and the expiry must leave alone): after a library sync, on an unmetered link
only, the chosen library's missing covers are fetched one at a time, hidden
libraries skipped, stepping aside for a starting stream. The decisions are in
`CoverSync`, which touches nothing and is tested against fakes.

**Every socket goes through `NetworkGate`.** Wi-Fi Only is a wall at the
transport, not a set of checks: HTTP clients are built with
`NetworkGate.httpClient`, the player streams only through `StreamProxy`, whose
upstream is one of those clients, and the raw paths ask `NetworkGate.isOpen()`.
A bare `HttpClient(OkHttp)` or `URL.openConnection()` anywhere else is a hole in
that wall — the courtesy checks (`metadataAllowed`, `heavyDataAllowed`) only
save work, they stop nothing. DLNA speakers are reached outside it, so
`DlnaCast.allowsNetwork` stands in front of every call there: closed until
`DlnaFamily.create` wires it to the link's rules.

**A protocol's folder is fenced.** `SourceBoundaryTest` fails when anything
outside `source/<kind>/` names that package, except `Providers.kt` (its provider
only) and the listed allowances, each saying what removes it. It also fails
when shared code names a kind's `SourceKind` constant: ask the provider
instead. Kotlin's `internal` can't do this, because Amp is one module.

**Conditional composable calls stop subscribing.** A `collectAsState` behind an
`&&` or inside an `if` silently stops triggering recomposition. Read
unconditionally, branch on the result.

**The player handle doesn't live as long as playback.** Backing out releases it
while the detached service plays on, and a reopen binds a new one. A new
activity — Android having destroyed the last one while the process lived —
takes the player over with `handOver`, which only swaps the handle: none of the
back-out's teardown, no new bind, and `player` goes from the old handle straight
to the new one, never through null. Everything on one handle starts in
`attachHandle` and lives in `handleJob`. Its flows hold placeholders until it
connects, so the collectors that read the service's state go in
`collectHandle`, which starts only after `awaitReady`; one started any earlier
copies 0:00 and "paused" over what the screen carried. (The device volume is
the system's own level and is read at once.) A service that moved to another
track meanwhile is landed on before they start, at its own position
(`connectLanding`, `landedOn`). The rest of `bind()`'s collectors live in
`bindJob`, outlive the handle on purpose, and are replaced by the next bind; a
handover leaves them be. A released handle throws on any command — on Main
that takes the process and the playing service with it — so a deferred block
reads `player` again on Main rather than using the `p` it captured. If its work
is for the service and the state has already been changed to match, it goes to
whatever handle is there (`player ?: return`). It drops with `player !== p`
only when the work belongs to the handle it checked: its connection, its gain,
a missed seek's reload (`verifyNativeSeek`, `serverSeek`), the stream-died
retry in `fallBackOffline`. `reresolveQueue` drops its edits the same way, but
puts its memo back to what the service holds and asks again, so the next
connected handle still gets them.

**Room schemas live in `src/main/assets/schemas`.** Light's store builder copies
only `src/main/{kotlin,java,res,assets}`, `build.gradle.kts` and
`lighttool.toml` out of the repository, so schemas anywhere else leave its build
without the files the auto-migrations are generated from. The asset packager is
told to leave them out of the APK.

**`Icons` in `ui/components` is Amp's own.** The Material glyphs are vendored in
`MaterialGlyphs.kt`, because `material-icons-extended` isn't in the store
builder's offline cache. To add one, list it in `MaterialGlyphsTest` with `null`;
the failure prints the source to paste.

**The store build is one command away.** `scripts/store-build-check.sh` builds
Amp the way Light will — pristine SDK, their extractor, their flags — and lists
what stops it. New code shouldn't add to that list.

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
