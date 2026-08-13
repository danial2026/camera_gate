#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate full project check: compiles debug + release APKs and runs
# Android lint.
#
# Usage:
#   scripts/check.sh           # assembleDebug + lint
#   scripts/check.sh --release # also assembleRelease (signed APK)
#
# Any failing step aborts with a non-zero exit code.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

# Gradle needs a JDK; prefer JAVA_HOME, then Android Studio's bundled JBR.
if [ -z "${JAVA_HOME:-}" ] && [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  echo "Using Android Studio JBR: $JAVA_HOME"
fi

DO_RELEASE=false
for arg in "$@"; do
  case "$arg" in
    --release) DO_RELEASE=true ;;
    *)
      echo "Unknown flag: $arg"
      exit 1
      ;;
  esac
done

echo "==> assembleDebug"
./gradlew assembleDebug

echo "==> lint"
./gradlew lint

if [ "$DO_RELEASE" = true ]; then
  echo "==> assembleRelease"
  ./gradlew assembleRelease
fi

echo "All checks passed."