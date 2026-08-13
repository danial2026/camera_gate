#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate build helper.
#
# Usage:
#   scripts/build.sh           # debug APK (fast)
#   scripts/build.sh release   # signed release APK
#   scripts/build.sh clean     # wipe build outputs (keeps gradle caches)
#
# APK output: app/build/outputs/apk/{debug,release}/
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

# Gradle needs a JDK; prefer JAVA_HOME, then Android Studio's bundled JBR.
if [ -z "${JAVA_HOME:-}" ] && [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

case "${1:-debug}" in
  debug)
    ./gradlew assembleDebug
    ;;
  release)
    ./gradlew assembleRelease
    ;;
  clean)
    ./gradlew clean
    ;;
  *)
    echo "Usage: scripts/build.sh [debug|release|clean]"
    exit 1
    ;;
esac

APK=$(ls -t app/build/outputs/apk/*/cameragate-v*.apk 2>/dev/null | head -1)
[ -n "$APK" ] && echo "APK: $APK"