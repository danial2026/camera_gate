#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate dev tooling wrapper.
#
# Usage:
#   scripts/dev.sh apk         # assembleDebug -> app/build/outputs/apk/debug
#   scripts/dev.sh release     # assembleRelease (signed with app/key.properties)
#   scripts/dev.sh install     # install debug APK on a connected device
#   scripts/dev.sh icon        # regenerate all launcher/splash/notification icons
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] && [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

case "${1:-}" in
  apk)
    ./gradlew assembleDebug
    echo "APK: app/build/outputs/apk/debug/"
    ;;
  release)
    ./gradlew assembleRelease
    echo "APK: app/build/outputs/apk/release/"
    ;;
  install)
    APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
    echo "Installing $APK"
    adb install -r "$APK"
    ;;
  icon)
    python3 scripts/generate_icon.py
    ;;
  *)
    echo "Usage: scripts/dev.sh [apk|release|install|icon]"
    exit 1
    ;;
esac