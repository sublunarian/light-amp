#!/usr/bin/env bash
#
# Build Amp the way Light's store builder would, and say what stops it.
#
#   scripts/store-build-check.sh                 quick: pristine SDK + extracted tool
#   scripts/store-build-check.sh --offline       faithful: also Light's offline
#                                                dependency cache (slow the first time)
#   scripts/store-build-check.sh --sdk-ref REF   build against another SDK commit
#                                                (default: the submodule's pin)
#   scripts/store-build-check.sh --head          check the last commit, not the
#                                                working tree
#
# Light signs a tool by building it themselves (light-sdk/builder): a pristine
# SDK baked into an image, into which their extractor copies only
# tool/lighttool.toml, tool/build.gradle.kts and tool/src/main/{kotlin,java,res,
# assets}, followed by `:tool:assembleRelease --offline -DlightSdk.unsigned=true`.
# None of light-sdk-patch/ and neither variant manifest overlay gets there. This
# script repeats those steps here — with Light's own extractor, so its rules are
# theirs and not a copy — and sums up the compile errors by file and by symbol.
#
# --offline repeats the image's warm-up too: the pristine SDK's sample tool is
# built into a Gradle home of its own, and Amp is then built against that home
# with the network off. A dependency on the plugin's allow-list can still be
# missing from that cache; this is the only way to find out before Light does.
#
# Exit status: 0 when the store build succeeds, 1 when it doesn't, 2 on misuse.
# Everything lands in build/store-check/ (ignored by git); nothing else is touched.

set -Eeuo pipefail
cd "$(dirname "$0")/.."

ROOT="$(pwd)"
SDK="light-sdk"
OUT="$ROOT/build/store-check"
SDK_REF="HEAD"
OFFLINE=0
FROM_HEAD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --offline) OFFLINE=1; shift ;;
        --head) FROM_HEAD=1; shift ;;
        --sdk-ref) SDK_REF="${2:?--sdk-ref needs a commit}"; shift 2 ;;
        -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

[[ -d "$SDK/builder/lightbuilder" ]] || {
    echo "light-sdk has no builder/ — run: git submodule update --init" >&2; exit 2; }

WORKSPACE="$OUT/workspace"
DEV_REPO="$OUT/dev-repo"
REPORT="$OUT/report"
LOG="$OUT/build.log"
rm -rf "$WORKSPACE" "$DEV_REPO" "$REPORT"
mkdir -p "$WORKSPACE" "$DEV_REPO" "$REPORT"
: > "$LOG"

# The SDK as Light has it: the commit, not the submodule's working tree, which
# normally carries Amp's patches.
SDK_SHA="$(git -C "$SDK" rev-parse --short "$SDK_REF")"
echo ">> pristine SDK @ $SDK_SHA"
git -C "$SDK" archive "$SDK_REF" | tar -x -C "$WORKSPACE"

# The tool as a clone would have it: tracked files only, so a stray .DS_Store
# or an ignored keystore can't change the answer. The working tree by default,
# because the point is usually to check work in progress.
if [[ $FROM_HEAD -eq 1 ]]; then
    echo ">> tool from the last commit ($(git rev-parse --short HEAD))"
    git archive HEAD -- tool | tar -x -C "$DEV_REPO"
else
    echo ">> tool from the working tree"
    git ls-files -z --cached --others --exclude-standard -- tool |
        while IFS= read -r -d '' f; do
            [[ -f "$f" || -L "$f" ]] || continue   # deleted, not yet committed
            mkdir -p "$DEV_REPO/$(dirname "$f")"
            cp -P "$f" "$DEV_REPO/$f"
        done
fi

echo ">> staging with Light's extractor"
if ! PYTHONPATH="$WORKSPACE/builder" python3 -m lightbuilder prepare \
        --dev-repo "$DEV_REPO" \
        --workspace-tool "$WORKSPACE/tool" \
        --tool-path tool \
        --output-dir "$REPORT" >>"$LOG" 2>&1; then
    echo "!! the extractor refused the tool:" >&2
    cat "$REPORT/error.json" 2>/dev/null >&2 || tail -5 "$LOG" >&2
    exit 1
fi

# Where the Android SDK is, if this machine says so in local.properties.
[[ -f local.properties ]] && cp local.properties "$WORKSPACE/"

GRADLE_ARGS=(":tool:assembleRelease" "--no-daemon" "--no-build-cache"
             "--continue" "-DlightSdk.unsigned=true")

if [[ $OFFLINE -eq 1 ]]; then
    # Light's image warms its cache by building the SDK's own sample tool, and
    # that is all the cache ever holds. One home per SDK commit.
    export GRADLE_USER_HOME="$OUT/gradle-home-$SDK_SHA"
    if [[ ! -f "$GRADLE_USER_HOME/.warmed" ]]; then
        echo ">> warming an offline cache from the SDK's sample tool (slow, once per SDK commit)"
        WARM="$OUT/warm"
        rm -rf "$WARM"; mkdir -p "$WARM"
        git -C "$SDK" archive "$SDK_REF" | tar -x -C "$WARM"
        [[ -f local.properties ]] && cp local.properties "$WARM/"
        (cd "$WARM" && ./gradlew :tool:assembleRelease --no-daemon) >>"$LOG" 2>&1 || {
            echo "!! the pristine SDK's sample tool did not build — see $LOG" >&2; exit 1; }
        rm -rf "$WARM"
        touch "$GRADLE_USER_HOME/.warmed"
    fi
    GRADLE_ARGS+=("--offline")
fi

echo ">> ./gradlew ${GRADLE_ARGS[*]}"
set +e
(cd "$WORKSPACE" && ./gradlew "${GRADLE_ARGS[@]}") >>"$LOG" 2>&1
STATUS=$?
set -e

if [[ $STATUS -eq 0 ]] && grep -q "^BUILD SUCCESSFUL" "$LOG"; then
    APK="$(find "$WORKSPACE/tool/build/outputs/apk/release" -name '*.apk' | head -1)"
    echo "== store build: OK — ${APK#"$ROOT"/}"
    exit 0
fi

ERRORS="$(grep -cE '^e: ' "$LOG" || true)"
echo "== store build: FAILED — $ERRORS compiler errors (full log: ${LOG#"$ROOT"/})"
if [[ "$ERRORS" -gt 0 ]]; then
    echo
    echo "by file:"
    grep -E '^e: ' "$LOG" | grep -v '^e: \[ksp\]' |
        sed -E 's#^e: file://[^ ]*/src/main/(kotlin|java)/##; s#:[0-9]+:[0-9]+ .*##' |
        sort | uniq -c | sort -rn | sed 's/^/  /'
    echo
    echo "by missing symbol:"
    grep -E '^e: ' "$LOG" | grep -oE "Unresolved reference '[^']+'" |
        sed -E "s/Unresolved reference '([^']+)'/\1/" |
        sort | uniq -c | sort -rn | sed 's/^/  /'
    KSP="$(grep -E '^e: \[ksp\]' "$LOG" | sed -E 's#^e: \[ksp\] [^ ]*/##' | sort -u || true)"
    if [[ -n "$KSP" ]]; then
        echo
        echo "from the annotation processor:"
        echo "$KSP" | sed 's/^/  /'
    fi
fi
# Whatever isn't a compiler error: a dependency the offline cache lacks, a
# plugin violation, a manifest the generator refused.
grep -E "Could not resolve|No cached version|Light SDK violation|not allowed|What went wrong" -A2 "$LOG" |
    grep -vE '^--$' | sort -u | head -20 | sed 's/^/  /' || true
exit 1
