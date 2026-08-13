# Changelog

All notable changes to CameraGate are documented in this file.

## [0.0.2] - 2026-08-13

Face detection release.

- Pure-Java Viola-Jones face detection engine (`core/face`): faithful port of
  OpenCV's Haar cascade classifier (integral/square/tilted images,
  variance-normalized stump evaluation) with the bundled
  `haarcascade_frontalface_alt` model — no OS or NDK dependency
- Hacker-style green targeting boxes drawn on the stream; frames scanned
  upright and rotated 90° since the legacy Camera API stream is never rotated
- Detection tunables in settings: max faces (1..8), finest scan scale
  (1/4 .. full res), scan interval (60..2000 ms), contrast boost (1.0..2.0),
  full-res re-acquire probe
- Lifecycle-aware camera pipeline rebuild with dynamic UI controls and a
  dedicated camera streaming settings screen
- Host-side verification harness: `scripts/hosttest.sh` compiles the engine
  with the desktop JDK and runs 2200+ assertions — integral sums against
  brute force, hand-built HAAR/LBP micro-cascades, full-cascade parser
  bookkeeping, and the tilted-integral table against an independent
  translation of OpenCV's `integral_tilted_`

## [0.0.1] - 2026-08-13

Initial public release.

- HTTP + WebSocket camera server on the classic Camera API (Android 4.x+)
- Endpoints: state, snapshot, MJPEG stream, WebSocket stream, recording,
  QR code, health, OpenAPI spec, dashboard
- Optional token auth (header or query parameter)
- Foreground service with wake lock, AlarmManager watchdog and
  `onTaskRemoved` restart survive screen-off and swipe-away
- Settings: port, auth token, listen address, stream resolution,
  stream FPS cap, status overlay (OSD), camera id
- Language: full English + فارسی support, runtime language selector in
  settings (applies immediately), RTL layout for Persian, Latin digits
  for all numbers/timestamps
- In-app preview, QR code of the LAN URL, recording controls
- License: GPL-3.0-or-later