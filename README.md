# CameraGate

Turn an old Android phone into a self-hosted camera server. The app exposes
the phone's camera over your LAN through a tiny HTTP + WebSocket server, so
any computer — a Raspberry Pi, a home server, your laptop — can capture
snapshots, watch a live stream, or record video from the phone's camera.

```
Phone camera  ──►  HTTP/WebSocket server  ──►  LAN
                     (built into the app)
```

**Works on Android 4.x (API 16, e.g. an old Sony Xperia) up to the latest
Android.** The server is written in pure Java against the classic Camera
API, so no modern Android features are required on the device running it.

---

## Features

- `GET /camera` — camera & server state as JSON
- `GET /snapshot` — latest frame as JPEG
- `GET /stream` — MJPEG live stream (plays in any browser tab)
- `GET /ws` — WebSocket stream, binary JPEG frames
- `POST /record/start` / `POST /record/stop` — MP4 recording to `/sdcard/CameraGate`
- `GET /qr` — QR code (PNG) of the server URL
- `GET /health` — liveness probe
- `GET /swagger` — OpenAPI 3.0 spec
- `GET /` — dashboard HTML page
- optional token auth over HTTP headers or query parameter
- fully bilingual UI (English / فارسی) with a runtime language selector in
  settings; RTL layout for Persian, numbers stay Latin
- foreground service (notification + wake lock) keeps the camera alive with
  the screen off; an AlarmManager watchdog + `onTaskRemoved` restart make the
  server survive swipe-away and task killers (especially on Android 4.x)
- on-device face detection: a pure-Java Viola-Jones engine (a faithful port
  of OpenCV's Haar cascade classifier — integral images, tilted integrals,
  variance normalization — with the bundled `haarcascade_frontalface_alt`
  model, zero OS/NDK dependencies) draws hacker-style green targeting boxes
  on the stream; frames are scanned upright and rotated 90° (the legacy
  stream is never rotated), and scale, scan rate, max faces, contrast and a
  full-res re-acquire probe are tunable in settings
- host-side verification: the cascade engine is plain Java, so
  `scripts/hosttest.sh` compiles it with the desktop JDK and runs 2200+
  assertions against hand-built micro-cascades and an independent translation
  of OpenCV's math, all without a phone
- UI follows [UI_DESIGN_GUIDE.md](./UI_DESIGN_GUIDE.md) — pure-black
  engineering aesthetic, cards, monospace data

---

## Build

Requires JDK 17+ (Android Studio's bundled JBR works), Android SDK, Gradle.

```bash
scripts/build.sh               # debug APK
scripts/build.sh release       # signed release APK (app/key.properties)
scripts/check.sh --release     # compile + lint + release
scripts/hosttest.sh            # host-side cascade engine tests (no phone)
scripts/dev.sh icon            # regenerate launcher icons
scripts/run.sh                 # install + launch + logcat on the phone
scripts/run.sh release         # same with the release APK
scripts/device_test.sh         # full on-device smoke + resilience test
```

APK output: `app/build/outputs/apk/` — e.g.
`cameragate-v0.0.2-release.apk`.

`scripts/build.sh release` signs with `app/key.properties` +
`app/upload-keystore.jks` when present; on a fresh clone without them the
release build automatically falls back to debug signing (see
`app/build.gradle.kts`), so the project builds out of the box. Generate your
own keystore for real releases:

```bash
keytool -genkeypair -v -keystore app/upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=You,O=You,C=US"
```

…and write `storePassword`, `keyPassword`, `keyAlias=upload`,
`storeFile=app/upload-keystore.jks` into `app/key.properties`.

Install on the old phone via ADB or copying the APK to it.

---

## Usage

1. Open **CameraGate** on the phone, tap **Start server**.
2. The screen shows the LAN URL `http://<phone-ip>:8080/` and its QR code.
3. On another machine on the same WiFi, open that URL - the dashboard shows
   live links.

### API examples

```bash
# state
curl http://192.168.1.10:8080/camera

# one frame
curl -o frame.jpg http://192.168.1.10:8080/snapshot

# live stream - watch in the browser via http://192.168.1.10:8080/stream
# (or open the URL directly in VLC / ffplay)

# record 10 seconds and stop
curl -X POST http://192.168.1.10:8080/record/start
sleep 10
curl -X POST http://192.168.1.10:8080/record/stop

# WebSocket: connect to ws://192.168.1.10:8080/ws,
# you receive one binary JPEG message per frame
```

With a token configured in settings, add `?token=YOUR_TOKEN` to every
request, or send `Authorization: Bearer YOUR_TOKEN` / `X-CameraGate-Token`.

**Note:** while recording, the live streams pause - the classic Camera API
stops delivering preview frames to MediaRecorder, so `/snapshot`,
`/stream` and `/ws` answer `503 recording in progress`.

---

## Settings

| Setting | Meaning |
| --- | --- |
| Port | HTTP listener port (default 8080) |
| Auth token | empty = open on LAN; any value = required on every API call |
| Listen address | which interface to bind (`0.0.0.0` = all) |
| Stream resolution | preview/stream size: `AUTO` or a fixed size (e.g. `640x480`) |
| Stream FPS | encoder cap (5/10/15/20/30 FPS); `AUTO` = uncapped |
| Status overlay | green OSD (timestamp · FPS · battery) drawn on every frame |
| Face detection | hacker-style green targeting boxes around faces (Haar cascade engine) |
| Face max | maximum faces per scan (1..8) |
| Face finest scale | smallest scale probed (1/4 .. full res); smaller = smaller faces = slower |
| Face scan interval | ms between detection passes (60..2000) |
| Face contrast | analysis contrast boost (1.0 = off .. 2.0) |
| Face deep scan | full-resolution re-acquire probe while nothing is found |
| Language | UI language: English or فارسی — applies immediately |
| Camera id | `0` back camera, `1` front camera |

Changes apply after restarting the server.

---

## On-device testing

`scripts/device_test.sh` drives the real phone over ADB:

1. installs the release APK and launches the app
2. taps **START SERVER** via uiautomator
3. resolves the phone's LAN IP and hits every endpoint:
   `/health`, `/camera`, `/snapshot`, `/stream`, `/record/start|stop`,
   `/qr`, `/swagger`
4. resilience: screen off for 10 s, then swipe-away in recents — the server
   must stay reachable and the service/process alive
5. collects screenshots into `screenshot/` and logcat into `build/`

Requires the phone and the host on the same WiFi (the test goes through the
LAN, not adb forwarded — that does not exist on Android 4.x).

---

## Use cases

- security / baby monitor in the LAN
- workshop camera
- computer-vision and OCR pipelines (grab frames from `/snapshot`)
- QR / barcode scanning stations
- internal livestreams and demos
- testing camera-based software

---

## Layout

```
app/src/main/java/com/danials/cameragate/
    CameraGateApp.java        app-scoped singleton
    MainActivity.java         server UI, preview, QR, record buttons
    SettingsActivity.java     port / token / camera settings
    CameraGateService.java    foreground service + wake lock + watchdog
    core/
      CameraGate.java         facade: camera + HTTP + settings
      CameraSource.java       legacy Camera API + MediaRecorder recording
      JpegFrames.java         NV21 -> JPEG pipeline shared by all clients
      HttpServer.java         ServerSocket HTTP/1.1 + MJPEG + WebSocket
      Settings.java           SharedPreferences persistence
      face/
        Cascade.java          OpenCV cascade classifier evaluator (HAAR/LBP)
        CascadeParser.java    classic cascade XML parser
        FaceDetector.java     multi-scale scan driver with variance gate
        IntegralImage.java    integral / square / tilted integral images
        IntRect.java          integer rectangle
host-test/                    host-side cascade engine tests (FaceEngineTest.java)
scripts/generate_icon.py      launcher icon generator (Pillow)
scripts/{build,run,device_test,hosttest}.sh   build/run/test helpers
screenshot/                  on-device test evidence (device_test.sh)
UI_DESIGN_GUIDE.md           the app's UI style guide
```

Compatibility notes: Java 7-style source, no androidx dependencies, no
`java.time`, framework API levelled — verified for `minSdk 16`
(test target: Sony Xperia M2, Android 4.3).

---

## License

GPL-3.0-or-later — see [LICENSE](./LICENSE).