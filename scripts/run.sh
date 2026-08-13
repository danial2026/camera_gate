#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate run helper: install on the connected phone and launch.
#
# Usage:
#   scripts/run.sh             # install + launch debug APK
#   scripts/run.sh release     # install + launch release APK
#   scripts/run.sh logcat      # follow the app log on the device
#   scripts/run.sh stop        # force-stop the app on the device
#
# Requires one connected device (adb).
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-adb}"
PKG=com.danials.cameragate

if ! "$ADB" devices | grep -q "device$"; then
  echo "ERROR: no device connected (adb devices)"
  exit 1
fi

case "${1:-debug}" in
  debug)
    APK=$(ls -t app/build/outputs/apk/debug/cameragate-v*-debug.apk 2>/dev/null | head -1)
    if [ -z "$APK" ]; then
      scripts/build.sh debug
      APK=$(ls -t app/build/outputs/apk/debug/cameragate-v*-debug.apk | head -1)
    fi
    echo "==> installing $APK"
    "$ADB" install -r "$APK"
    echo "==> launching $PKG"
    "$ADB" shell am start -n "$PKG/.MainActivity"
    echo "==> live logcat (Ctrl-C to stop)"
    "$ADB" logcat -s CameraGate:* AndroidRuntime:E
    ;;
  release)
    APK=$(ls -t app/build/outputs/apk/release/cameragate-v*-release.apk 2>/dev/null | head -1)
    if [ -z "$APK" ]; then
      scripts/build.sh release
      APK=$(ls -t app/build/outputs/apk/release/cameragate-v*-release.apk | head -1)
    fi
    echo "==> installing $APK"
    "$ADB" install -r "$APK"
    echo "==> launching $PKG"
    "$ADB" shell am start -n "$PKG/.MainActivity"
    echo "==> live logcat (Ctrl-C to stop)"
    "$ADB" logcat -s CameraGate:* AndroidRuntime:E
    ;;
  logcat)
    "$ADB" logcat -s CameraGate:* AndroidRuntime:E
    ;;
  stop)
    "$ADB" shell am force-stop "$PKG"
    ;;
  *)
    echo "Usage: scripts/run.sh [debug|release|logcat|stop]"
    exit 1
    ;;
esac