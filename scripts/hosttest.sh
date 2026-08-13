#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate host-side tests: compiles the pure-Java cascade engine
# (core/face - no Android dependencies) with the host JDK and runs the
# verification harness, so parser + evaluator math are checked without a
# phone. Exits non-zero on any failed assertion.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] && [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
JAVAC="${JAVA_HOME}/bin/javac"
JAVA="${JAVA_HOME}/bin/java"
[ -x "$JAVAC" ] || { echo "javac not found (set JAVA_HOME)"; exit 1; }

SRC=app/src/main/java/com/danials/cameragate/core/face
TEST=host-test/src/com/danials/cameragate/core/face
OUT=build/host-test
RES=app/src/main/assets/cascades

mkdir -p "$OUT"

"$JAVAC" -d "$OUT" -source 8 -target 8 \
  "$SRC"/IntRect.java "$SRC"/IntegralImage.java "$SRC"/Cascade.java \
  "$SRC"/CascadeParser.java "$SRC"/FaceDetector.java "$TEST"/FaceEngineTest.java

# The cascade XML rides along as a resource so tests hit the exact bytes
# shipped in the APK.
rm -rf "$OUT/cascades"
cp -R "$RES" "$OUT/cascades"

echo "Running: $(basename "$TEST"/FaceEngineTest.java)"
"$JAVA" -cp "$OUT" com.danials.cameragate.core.face.FaceEngineTest
