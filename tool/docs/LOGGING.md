# Logging

What Amp writes down about itself, where it keeps it, and what it must never write.

## Where lines go

Every line goes through `com.sublunar.amp.log.Log` (`i`, `w`, `e`), which has `android.util.Log`'s shape. It does three things:

1. Scrubs the line (`LogScrubber`): URLs, query secrets, login headers, IPs, MACs, phone paths, emails, long ids and tokens become placeholders like `<url>`. This is a net under the rules below, not a replacement for them.
2. Writes it to logcat under its tag, as before, so `adb logcat -s 'AmpSync:*'` still works.
3. Keeps it in the phone's own log (`LogStore`): `files/logs/amp.log` and `amp.1.log` in Amp's private folder.

Nothing else may call `android.util.Log` (`LogRulesTest` checks).

The phone's own log exists because the LP3's logcat holds a minute or two (a 256 KiB buffer flooded by system spam), so a problem away from a cable left no evidence.

## Retention

- Two files of 512 KiB each, so at most 1 MiB, about 10,000 lines. A normal hour writes a few dozen lines.
- `amp.log` is started afresh when it fills or when its first line is 7 days old. `amp.1.log` is deleted 7 days after its last line. No line is kept much more than two weeks.
- Each tag may write 60 info lines a minute; the rest are counted (`AmpLog: 311 AmpArt lines left out`). Warnings and errors are never left out.
- Uninstalling Amp deletes the log. Holding the version number on Settings → About opens the Log page, which has no row of its own. Its Clear Log erases the log and the copies Amp saved.
- Its Save a Copy writes `Download/Amp/amp-log-<date>-<time>.txt`, which a computer connected by USB, or `adb pull`, can read. Amp never sends a log anywhere.

## What a line may say

Only facts about Amp itself. A line must still be safe to post in a public issue a week later.

Allowed: counts, sizes, durations, booleans, states and enum names, HTTP status codes, exception class names, format and bitrate, the data mode, the link (wifi, cellular, metered), Amp's own numbers (queue index, generation), and server software and version.

Instead of who or what, say which kind:

| Instead of | Write |
|---|---|
| a source's name or address | `source.logRef`: kind plus the end of Amp's random id, e.g. `subsonic/3fa2` |
| a host, IP or URL | the route: `lan`, `wan`, `relay`, `loopback`; `http` or `https`; an endpoint kind (`stream`, `download`, `cover`) |
| a track, album, playlist or artist | the queue index `idx=7` (the row in PlaybackController's queue), counts, or `file`/`download`/`stream` |
| a speaker or player | the family (`dlna`, `plex`, `airplay`), never its name, address, MAC or UDN |
| an exception | `e.logName()`: the class of each cause and the status of Amp's own exceptions. Where the stack matters, `e.logFrames(n)` |
| text a server or device sent (a version, a model) | `loggableWord(text)`: plain words and numbers, or `unknown` |

Never:

- passwords, tokens, salts, API keys, Authorization or gateway headers, cookies, login codes;
- a URL or any part of one: host, path or query. Paths carry ids and queries carry logins;
- an exception's message, `"$e"`, `e.toString()` or a stack trace with messages. ktor's timeout messages hold the whole request URL, the Subsonic login included. `Log` takes no Throwable for this reason;
- text from a server or device (error descriptions, SOAP faults, mDNS names, response bodies) other than through `loggableWord`;
- host names, IPs (the phone's own included), ports, MACs, UDNs, machine ids, the names of speakers and players;
- ids, titles or names of tracks, albums, artists, playlists or libraries, and paths on the phone (a phone file's track id is its path);
- user names, source names, account names, emails;
- whole objects (`"$source"`, `"$result"`). Log fields.

## What to log in normal use

One line per change of state, not per row, per cover or per poll.

- At start, the header: Amp version and build type, LightOS build, Android version, heap. Then the setup: each source's `logRef`, server software and version, qualities and offline mode; the active source, data mode, artwork, ReplayGain and storage limit.
- Every long job logs its start, its end with counts and how it ended, and when it is skipped or cancelled: library sync, cover sync, the download worker, lyrics lookups, casts. A device test then leaves its result behind, success included.
- Network: link changes, the rules they open, reachability and route changes by class, what the network gate refused or shut.
- Playback: how a row started (file, download, stream and format) and how long it took when Buffering showed, a start that never made a sound, a stream replaced by its download, player errors by the SDK's media3 code name.
- Casting: when a cast begins and ends, state changes, what the renderer reports after a push (ours, the previous stream, the next, another), whether it took the next track.
- Failures: what failed, `logName()`, the status, how long it took. A failure that repeats is logged when it starts and when it stops, not every time.

## Temporary lines

Instrumentation for one device test uses a tag starting `AmpTmp` (`AmpTmpSeek`). It goes into the file like any other line, so the test can be read afterwards. `scripts/release-mapping-check.sh` refuses a release while one is left.

## Crashes

`Log.attach` installs the default uncaught-exception handler. It writes `AmpCrash` lines (class names and frames, no messages) into the log, then hands Android a copy of the exception with every message removed, so the crash record Android files holds no URL. Android prints a crash thrown directly on a thread before any app handler runs; there the only defence is that network failures are caught where they happen.

## Logcat is not covered

Logcat and bug reports also hold what Android, media3, the SDK and OkHttp log in Amp's process, which `Log` never sees. Reading them needs adb or a system app.
