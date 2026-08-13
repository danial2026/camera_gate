# Changelog

All notable changes to CameraGate are documented in this file.

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