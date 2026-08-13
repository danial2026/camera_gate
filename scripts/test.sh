#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate test runner (native Android project).
#
# Usage:
#   scripts/test.sh            # assembleDebug + lint (no hardware needed)
#   scripts/test.sh -f         # fast: compile only (assembleDebug)
#
# On-device behavior (camera server, streaming) requires a real phone -
# the old Sonys are perfect for that.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] && [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

case "${1:-}" in
  -f|--fast)
    ./gradlew assembleDebug
    ;;
  -l|--lint)
    ./gradlew lint
    ;;
  *)
    ./gradlew assembleDebug lint
    ;;
esac

echo "Tests passed."