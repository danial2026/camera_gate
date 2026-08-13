#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# CameraGate on-device smoke test.
#
# Install the release APK on the connected phone, start the server from the
# UI, then exercise every HTTP endpoint over the LAN and verify resilience
# (screen off, swipe-away). Evidence lands in screenshot/ and build/.
#
# Tested target: Sony Xperia M2 (D2302, Android 4.3, API 18).
#
# Usage: scripts/device_test.sh            # full run
#        scripts/device_test.sh --build    # also rebuild the release APK first
# -----------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-adb}"
PKG=com.danials.cameragate
ACTIVITY="$PKG/.MainActivity"
APK=$(ls -t app/build/outputs/apk/release/cameragate-v*-release.apk 2>/dev/null | head -1)
PORT=8080
PASS=0; FAIL=0
LOG=build/device_test.log
mkdir -p build screenshot
: > "$LOG"

log()  { echo "==> $*" | tee -a "$LOG"; }
ok()   { PASS=$((PASS+1)); echo "    PASS $*" | tee -a "$LOG"; }
fail() { FAIL=$((FAIL+1)); echo "    FAIL $*" | tee -a "$LOG"; }

if [ "${1:-}" = "--build" ] || [ -z "$APK" ]; then
  log "building release APK"
  scripts/build.sh release || { echo "BUILD FAILED"; exit 1; }
  APK=$(ls -t app/build/outputs/apk/release/cameragate-v*-release.apk | head -1)
fi

if ! "$ADB" devices | grep -q "device$"; then
  echo "ERROR: no device connected"; exit 1
fi

phone_ip() {
  local ip
  ip=$("$ADB" shell getprop dhcp.wlan0.ipaddress 2>/dev/null | tr -d '\r')
  [ -z "$ip" ] && ip=$("$ADB" shell "ip addr show wlan0" 2>/dev/null |
    grep -o 'inet [0-9.]*' | head -1 | cut -d' ' -f2)
  echo "$ip"
}

lock_screen() {
  # Wake the screen and dismiss the keyguard (swipe up, then MENU as
  # fallback) so UI automation can see the app.
  "$ADB" shell input keyevent 224
  sleep 1
  "$ADB" shell input swipe 270 800 270 100 200
  sleep 1
  "$ADB" shell input keyevent 82
  sleep 1
}

tap_text() {
  # Scroll the ScrollView section by section until the node with the exact
  # text is visible, then tap its center. (uiautomator only dumps visible
  # nodes, so off-screen buttons need scrolling first.)
  local text="$1" bounds="" i
  for i in 1 2 3 4 5 6; do
    "$ADB" shell uiautomator dump /sdcard/ui_cg.xml >/dev/null 2>&1
    local xml
    xml=$("$ADB" shell cat /sdcard/ui_cg.xml 2>/dev/null)
    bounds=$(printf '%s' "$xml" |
      grep -o 'text="'"$text"'"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' |
      grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
    [ -n "$bounds" ] && break
    "$ADB" shell input swipe 270 700 270 100 300
    sleep 1
  done
  if [ -z "$bounds" ]; then
    echo ""
    return 1
  fi
  local l t r b x y
  l=$(sed 's/\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1/' <<<"$bounds")
  t=$(sed 's/\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\2/' <<<"$bounds")
  r=$(sed 's/\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\3/' <<<"$bounds")
  b=$(sed 's/\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\4/' <<<"$bounds")
  x=$(( (l + r) / 2 )); y=$(( (t + b) / 2 ))
  echo "$x $y"
}

scroll_top() {
  "$ADB" shell input swipe 270 200 270 700 200
  sleep 1
}

last_shot() { ls -t screenshot/*.png 2>/dev/null | head -1; }

log "=== CameraGate device test $(date '+%F %T') ==="

log "installing $APK"
"$ADB" install -r "$APK" 2>&1 | tail -1
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" logcat -c 2>/dev/null

log "waking screen + dismissing keyguard"
lock_screen

log "launching app"
"$ADB" shell am start -n "$ACTIVITY" >/dev/null 2>&1
sleep 4
"$ADB" shell screencap -p /sdcard/ui_1_stopped.png >/dev/null 2>&1
"$ADB" pull /sdcard/ui_1_stopped.png screenshot/ >/dev/null 2>&1 && ok "screenshot ui_1_stopped.png"

log "tapping START SERVER"
XY=$(tap_text "START SERVER")
if [ -n "$XY" ]; then
  "$ADB" shell input tap $XY
  ok "tapped START SERVER at ($XY)"
else
  fail "could not find START SERVER button"
fi

IP=$(phone_ip)
if [ -z "$IP" ]; then
  fail "could not resolve phone IP"; echo "IP=$IP PORT=$PORT"; sed 's/^/    /' "$LOG"
else
  ok "phone IP=$IP"
fi
BASE="http://$IP:$PORT"

log "waiting for server (health poll)"
SERVER_UP=0
for i in $(seq 1 40); do
  if curl -s --max-time 2 "$BASE/health" >/dev/null 2>&1; then SERVER_UP=1; break; fi
  sleep 1
done
[ "$SERVER_UP" = 1 ] && ok "server reachable" || fail "server never reachable"

log "screenshots while running"
scroll_top
"$ADB" shell screencap -p /sdcard/ui_2_running.png >/dev/null 2>&1
"$ADB" pull /sdcard/ui_2_running.png screenshot/ >/dev/null 2>&1

log "GET /health"
H=$(curl -s --max-time 5 "$BASE/health")
echo "$H" | grep -q '"ok":true' && ok "$H" || fail "unexpected: $H"

log "GET /camera"
C=$(curl -s --max-time 5 "$BASE/camera")
echo "$C" | grep -q '"version":"0.3.0"' && ok "version 0.3.0" || fail "version missing: $C"
echo "$C" | grep -q '"open":true' && ok "camera open" || fail "camera not open: $C"

log "GET /snapshot"
S=build/snapshot.jpeg
curl -s --max-time 8 -o "$S" "$BASE/snapshot"
SZ=$(stat -f%z "$S" 2>/dev/null || stat -c%s "$S" 2>/dev/null)
if [ "${SZ:-0}" -gt 10000 ] && file "$S" | grep -qi jpeg; then
  ok "snapshot $SZ bytes, JPEG"
else
  fail "snapshot bad ($SZ bytes)"
fi

log "GET /stream (3s)"
curl -s --max-time 4 -o build/stream.mjpeg "$BASE/stream" 2>/dev/null
FR=$(grep -c -- '----cameragateframe' build/stream.mjpeg 2>/dev/null || true)
[ "${FR:-0}" -ge 2 ] && ok "mjpeg stream, $FR frames" || fail "mjpeg stream bad ($FR)"

log "POST /record/start -> wait 4s -> stop"
RS=$(curl -s --max-time 5 -X POST "$BASE/record/start")
echo "$RS" | grep -q '"recording"' && ok "record started" || fail "record start: $RS"
sleep 4
"$ADB" shell screencap -p /sdcard/ui_3_recording.png >/dev/null 2>&1
"$ADB" pull /sdcard/ui_3_recording.png screenshot/ >/dev/null 2>&1
RE=$(curl -s --max-time 8 -X POST "$BASE/record/stop")
echo "$RE" | grep -q '"status":"saved"' && ok "record stopped+$RE" || fail "record stop: $RE"
MP4=$(echo "$RE" | sed -n 's/.*"file":"\([^"]*\)".*/\1/p')
# The JSON path uses /storage/emulated/0, but 4.3 legacy-mount devices map
# external storage to /storage/emulated/legacy. Pull via that real root and
# verify nonzero size.
LOCDIR="/storage/emulated/legacy/CameraGate"
LOC=$("$ADB" shell "ls -t $LOCDIR/" 2>/dev/null | tr -d '\r' | grep -m1 '\.mp4')
echo "    newest recording: $LOC"
if [ -n "$LOC" ] && "$ADB" pull "$LOCDIR/$LOC" build/recording.mp4 >/dev/null 2>&1 \
   && [ -s build/recording.mp4 ]; then
  ok "recording file valid (build/recording.mp4, $(stat -f%z build/recording.mp4) bytes)"
else
  fail "could not pull recorded file"
fi

log "GET /qr"
Q=build/qr.png
curl -s --max-time 5 -o "$Q" "$BASE/qr"
[ -s "$Q" ] && file "$Q" | grep -qi png && ok "qr png" || fail "qr png bad"

log "GET /swagger"
SW=$(curl -s --max-time 5 "$BASE/swagger")
echo "$SW" | grep -q '0.3.0' && ok "swagger 0.3.0" || fail "swagger"

log "=== resilience: screen off (10s) ==="
"$ADB" shell input keyevent 26; sleep 10
curl -s --max-time 5 "$BASE/health" | grep -q '"ok":true' && ok "health ok with screen off" || fail "health failed screen-off"
"$ADB" shell input keyevent 26; sleep 2

log "=== resilience: swipe away in recents ==="
lock_screen
"$ADB" shell input keyevent 187; sleep 2
"$ADB" shell input swipe 540 350 100 350 250
sleep 10
curl -s --max-time 5 "$BASE/health" | grep -q '"ok":true' && ok "health ok after swipe-away" || fail "health failed after swipe-away"
"$ADB" shell dumpsys activity services "$PKG" 2>/dev/null | grep -q "ServiceRecord" && ok "service alive" || fail "service gone"
"$ADB" shell "ps | grep $PKG" >/dev/null 2>&1 && ok "process alive" || fail "process gone"
"$ADB" shell screencap -p /sdcard/ui_4_after_swipe.png >/dev/null 2>&1
"$ADB" pull /sdcard/ui_4_after_swipe.png screenshot/ >/dev/null 2>&1

log "=== log buffers ==="
"$ADB" logcat -d -s CameraGate:* AndroidRuntime:E > "$LOG.logcat" 2>/dev/null
grep -qi 'error\|exception' "$LOG.logcat" && \
  { echo "    WARN: errors in logcat:"; grep -i 'error\|exception' "$LOG.logcat" | head -5; } || ok "no errors in logcat"

log "restoring screen state (wake + return to app)"
lock_screen
"$ADB" shell am start -n "$ACTIVITY" >/dev/null 2>&1
sleep 2

log "=== summary: PASS=$PASS FAIL=$FAIL ==="
echo "screenshots:"; ls -1 screenshot/*.png 2>/dev/null | sed 's/^/  /'
echo "log: $LOG (+ .logcat twinned)"
[ "$FAIL" = 0 ]