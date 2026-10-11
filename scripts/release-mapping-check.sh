#!/usr/bin/env bash
#
# Check a release build's R8 mapping for the two names the late-unbind guard
# can't do without.
#
#   scripts/release-mapping-check.sh             the last local release build
#   scripts/release-mapping-check.sh MAPPING     any other mapping.txt
#
# The guard (tool/src/main/kotlin/com/sublunar/amp/playback/LateUnbind.kt)
# knows the crash it absorbs only by media3's class names: the message names
# media3's SessionServiceConnection, and the stack its MediaControllerImplBase.
# A build that renamed them — keep-names.txt not applied, or a newer R8 or
# media3 merging the class away — compiles, installs and plays exactly as
# before, while the guard quietly matches nothing and the crash is back. A
# compile can't show that and the unit tests use made-up stacks; only the
# mapping does.
#
# The second name is the harder one to keep. The frame the stack check reads
# is the synthetic class R8 puts media3's posted release runnable in, and
# -dontobfuscate doesn't stop R8 from merging lambdas across classes: some of
# MediaControllerImplBase's already run inside other libraries' synthetic
# lambdas. So the check finds the class that runs lambda$release$N as `run`
# and asks that it still carries media3's controller name, as it did in 0.7.2
# ($$ExternalSyntheticLambda52) and does now (...Lambda59).
#
# Run it after `./gradlew :tool:assembleRelease` and before tagging, before the
# APK and its mapping are copied anywhere: the local release build is the one
# that ships. scripts/store-build-check.sh runs it on the store build too. Not
# a Gradle task, because Light's extractor copies tool/build.gradle.kts into
# their builder, and this checks Amp's builds rather than being one of theirs.
#
# Exit status: 0 when both are there, 1 when either isn't, 2 on misuse or when
# there is no mapping to read.

set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

case "${1:-}" in
    -h|--help) sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac
[[ $# -le 1 ]] || { echo "usage: $0 [mapping.txt]" >&2; exit 2; }

MAPPING="${1:-$ROOT/tool/build/outputs/mapping/release/mapping.txt}"
[[ -f "$MAPPING" ]] || {
    echo "no mapping at $MAPPING — build first: ./gradlew :tool:assembleRelease" >&2; exit 2; }

KEPT='androidx.media3.session.MediaControllerImplBase$SessionServiceConnection -> androidx.media3.session.MediaControllerImplBase$SessionServiceConnection:'
if ! grep -qF "$KEPT" "$MAPPING"; then
    echo "!! media3's SessionServiceConnection is renamed or missing in ${MAPPING#"$ROOT"/}" >&2
    echo "   the late-unbind guard can't recognise its crash in this build —" \
         "see tool/src/main/assets/r8/keep-names.txt" >&2
    exit 1
fi
echo "== mapping: media3's SessionServiceConnection kept its name"

# The class whose `run` is media3's release lambda: the last class line above
# the member line that maps lambda$release$N onto run. Empty when R8 no longer
# inlines it there, which puts a frame of its own between that class and
# Handler.handleCallback — a stack the guard rejects as well.
HOST=$(awk '/^[^ #]/ { cls = $1 }
            /MediaControllerImplBase\.lambda\$release\$[0-9]+\(.*-> run$/ { print cls; exit }' "$MAPPING")
case "$HOST" in
    androidx.media3.session.MediaControllerImplBase*)
        echo "== mapping: media3's release runnable runs as ${HOST}.run" ;;
    *)
        echo "!! media3's release runnable runs as '${HOST:-<not found>}' in ${MAPPING#"$ROOT"/}" >&2
        echo "   the late-unbind guard's stack check needs a MediaControllerImplBase frame" \
             "there — see LateUnbind.kt" >&2
        exit 1 ;;
esac
