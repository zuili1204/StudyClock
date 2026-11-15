#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/gradlew" -p "$ROOT" :app:verifyReleaseSigning
"$ROOT/gradlew" -p "$ROOT" clean testDebugUnitTest lintRelease assembleRelease "$@"
bash "$ROOT/scripts/check-apk.sh" "$ROOT/app/build/outputs/apk/release/app-release.apk"
