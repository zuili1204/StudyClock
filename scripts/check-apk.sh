#!/usr/bin/env bash
set -euo pipefail
APK="${1:?Usage: check-apk.sh APK [SDK_DIRECTORY] [PREVIOUS_APK]}"
SDK="${2:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [[ -z "$SDK" ]]; then
    printf 'Set ANDROID_HOME or pass the SDK directory as argument 2.\n' >&2
    exit 1
fi
TOOLS="$SDK/build-tools/34.0.0"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVA" -jar "$TOOLS/lib/apksigner.jar" verify --verbose "$APK"
PERMISSIONS="$("$TOOLS/aapt2" dump permissions "$APK")"
printf '%s\n' "$PERMISSIONS"
if [[ "$PERMISSIONS" == *android.permission.INTERNET* || "$PERMISSIONS" == *android.permission.READ_EXTERNAL_STORAGE* || "$PERMISSIONS" == *android.permission.WRITE_EXTERNAL_STORAGE* || "$PERMISSIONS" == *android.permission.MANAGE_EXTERNAL_STORAGE* ]]; then
    printf 'Forbidden network/storage permission found.\n' >&2
    exit 1
fi
unzip -Z1 "$APK"
SIZE="$(wc -c < "$APK" | tr -d ' ')"
printf '\nAPK size: %s bytes\n' "$SIZE"
if [[ "$SIZE" -ge 15728640 ]]; then
    printf 'APK exceeds the 15 MiB budget.\n' >&2
    exit 1
fi
if [[ -n "${3:-}" ]]; then
    PREVIOUS="$(wc -c < "$3" | tr -d ' ')"
    DELTA=$(( SIZE - PREVIOUS ))
    printf 'Size delta: %s bytes\n' "$DELTA"
    if (( DELTA > 1048576 || DELTA < -1048576 )); then
        printf 'Size changed by over 1 MiB; review and record the reason before release.\n' >&2
        exit 1
    fi
fi
