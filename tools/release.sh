#!/usr/bin/env bash
# Builds the release APK and publishes it as GitHub release v<versionName>.
# Floki Store finds it there on its next start and offers the update.
# Usage: tools/release.sh [notes.md]   (without notes GitHub writes them from the commits)
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk}"
./gradlew --offline -q :core:test :app:assembleRelease

version=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
apk="build/release/FlokiStore-$version.apk"
mkdir -p build/release
cp app/build/outputs/apk/release/app-release.apk "$apk"

if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
    echo "Tag v$version gibt es schon - erst versionCode/versionName in app/build.gradle.kts erhöhen." >&2
    exit 1
fi
notes=(--generate-notes)
[ $# -ge 1 ] && notes=(--notes-file "$1")
gh release create "v$version" "$apk" --repo chris2crafter-ops/FlokiStore --title "Floki Store $version" "${notes[@]}"
