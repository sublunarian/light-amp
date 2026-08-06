# Contributing to Amp

Amp is a music player for the Light Phone III. This repository is a fork of
[lightphone/light-sdk](https://github.com/lightphone/light-sdk) with the app in
its `tool/` module, because that is the shape a LightOS tool takes — a Gradle
module inside the SDK rather than a project that depends on it.

**That means most of what is here is Light's, not ours.** Please send anything
about the SDK itself upstream rather than here:

| | |
|---|---|
| `tool/` | Amp. Issues and pull requests belong here. |
| `sdk/`, `plugin/`, `builder/`, `examples/`, `lint-rules/` | Light's. See [their contributing guide](docs/light-sdk-CONTRIBUTING.md) and file upstream. |

The exception is the handful of SDK changes Amp needs to work at all. Those are
documented, marked in the source, and listed with revert steps in
[SDK-PATCHES](tool/docs/SDK-PATCHES.md). Find every one of them with:

```bash
grep -rn "SDK PATCH\|SPIKE\|TEMPORARY" sdk/ tool/src
```

If a change isn't marked, it isn't ours.

## Before opening a pull request

- **Say what problem it solves.** A bug report with reproduction steps is worth
  more than a patch without one.
- **Make it build.** `./gradlew :tool:compileDebugKotlin` at minimum; `./gradlew
  check` if you have touched anything shared.
- **Try it on a phone.** The LP3's panel, its greyscale filter and its sandbox
  all behave differently from an emulator, and most of the interesting bugs in
  this app only appeared on hardware.
- **Match the code around you.** Sizes are in the LP3's own pixels via `px()`
  and `pxSp()`, never raw `dp`. Comments explain *why*, not what.

## What is unlikely to be merged

- Features that only work on one kind of server. A capability a backend cannot
  support should be *absent* on that backend, not present and broken.
- Silent fallbacks. If someone picks FLAC and FLAC fails, it must fail —
  substituting something they didn't choose is worse than an error.
- Anything that needs a permission or an API outside the SDK's allow-list,
  unless it comes with a plan for removing it again.

## Reporting a security issue

Please don't open a public issue. Use GitHub's private vulnerability reporting
on this repository, or contact [@sublunarian](https://github.com/sublunarian)
directly.

## A note on AI assistance

Parts of this codebase were written with an LLM in the loop, and that is not
something to hide. If you contribute the same way, the bar is the one Light sets
upstream and it is a fair one: you must be able to explain your change in your
own words, comments must be brief and in your voice rather than a transcript of
a session, and you are responsible for what you submit. Note that Light's
[AI policy](docs/light-sdk-CONTRIBUTING.md) governs anything sent upstream to
them, including the SDK patches above.
