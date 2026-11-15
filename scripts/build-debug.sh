#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/gradlew" -p "$ROOT" testDebugUnitTest assembleDebug "$@"
printf '\nDebug APK: %s\n' "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
