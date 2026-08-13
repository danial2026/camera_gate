# Changelog

All notable changes to CameraGate are documented in this file.

## [0.0.4] - 2026-08-13

Face detection recall + latency release.

- The scanner now feeds on the clean pre-overlay frame: previously it
  decoded the re-encoded, OSD-stamped JPEG, so the green status text and
  the last frame's own face boxes fed back into the next scan
- Scan bitmaps decode to ARGB_8888 instead of RGB_565: `getPixels` on
  565 buffers returns raw packed values on older Android, which the
  luma formula misread (measured: a 40px face that all other paths find
  was lost); 8-bit channels also restore full dynamic range
- The full-frame downscale into the scan bitmaps is bilinear
  (`FILTER_BITMAP_FLAG`) instead of nearest-neighbour, which aliased
  away small faces
- Scan cadence is capped at 1s: a slow pass previously stretched the
  next interval to 3x its cost, so boxes fell seconds behind live motion
- Rotation strategy reworked: cheap coarse scales scan both orientations
  every slot (sideways faces are found on the first look), fine scales
  alternate and prefer the rotation that last found a face, probing the
  other rotation on a slow beat; hunting was dropped from every 4th slot
  at 1/2 scale to every 8th
- The interval between scans is now measured from the end of a pass, not
  its start, so a slow pass no longer delays the next scan by its own
  duration
- The scan level now escalates on empty runs and never drops while a
  face is found: previously, five consecutive detections walked the scan
  back to a coarser scale, the still-visible face went marginal there,
  and the boxes blinked on and off every couple of seconds (and cut
  effective recall to a fraction of live time); the scale only relaxes
  after 8 consecutive empty slots, so an idle scene scans cheaply again
- Scanning starts at 1/3 scale instead of 1/4, so faces down to ~65px
  are found on the first slot and the finest level is reached in half
  the empty runs
- Version bump to 0.0.4

## [0.0.3] - 2026-08-13

Stream stability + detection recall release.

- Face detection moved to its own background thread (`cameragate-scanner`):
  a slow Haar pass (seconds on old phones) no longer blocks the converter,
  so the stream keeps running at its full rate while faces are scanned;
  the scanner always re-scans the newest frame, never a backlog, and the
  boxes simply update less often when passes are expensive
- Adaptive scan pacing: the interval between passes grows with the measured
  pass cost, and expensive passes cap at 1/2 scale - fewer, coarser looks
  when the source is a compressed, low-quality stream
- Better detection recall with defaults: minimum-overlap strictness lowered
  (3 -> 2) for noisy compressed frames, scale escalation after 2 instead of
  4 empty runs, faster return to coarse levels once faces are tracked
- Stream writers (MJPEG + WebSocket) now throttle per client: every client
  gets the newest frame at most ~12 fps instead of every frame, so a slow
  viewer cannot accumulate backlog and drift minutes behind live time after
  hours of uptime; clients whose socket stalls for seconds are dropped so a
  zombie cannot occupy a pool thread forever
- Live in-app preview: while the server runs, the main screen's preview card
  shows the actual stream (overlay and face boxes included) instead of a
  static hint
- Documentation and comments brought in line with the new architecture
- Version bump to 0.0.3

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