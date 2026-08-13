package com.danials.cameragate.core;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.media.FaceDetector;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.danials.cameragate.BuildConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;

/**
 * Shared bridge between the camera and every HTTP/WebSocket consumer.
 *
 * Handles three jobs:
 * <ul>
 *   <li>Keeps a fixed pool of NV21 buffers handed to the legacy Camera
 *       API, recycling them as fast as possible (no per-frame allocation).</li>
 *   <li>Runs a single converter thread that turns NV21 frames into JPEG,
 *       so all consumers share one encode per frame.</li>
 *   <li>Stores the latest JPEG and notifies waiters when a new frame
 *       lands (MJPEG stream, WebSocket stream, snapshots).</li>
 * </ul>
 */
public final class JpegFrames {

    private static final String TAG = "CameraGate";

    private static final int BUFFER_COUNT = 6;
    private static final int QUEUE_CAPACITY = 4;

    public interface Listener {
        void onNewFrame();
    }

    private final Queue<byte[]> freeBuffers = new ArrayDeque<byte[]>();
    private final Queue<byte[]> pending = new ArrayDeque<byte[]>();

    private final Thread converter;
    private volatile boolean running = false;
    private volatile boolean shutdown = false;

    private final Object lock = new Object();
    private byte[] latestJpeg;
    private long latestSeq = 0;
    private int width;
    private int height;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    // ---- status overlay (green OSD) ----
    // Success green per UI_DESIGN_GUIDE.md: #00FF88
    private final Context appContext;
    private volatile boolean osdEnabled = true;
    private volatile String osdLabel;
    // 1-second-cached stats so /proc reads and ActivityManager calls never
    // run per frame (they would tax the 15fps pipeline on old devices)
    private String osdStats = null;
    private long statsAt = -1;
    private String osdMeta = null;
    private int metaW = -1;
    private int metaH = -1;
    private final SimpleDateFormat osdTime =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private Paint osdFill;
    private Paint osdStroke;
    private Bitmap osdCanvas;
    private BitmapFactory.Options osdOpts;
    private long fpsWindowStart = -1;
    private int fpsCount = 0;
    private volatile int fps = 0;

    // ---- frame-rate cap (0 = uncapped) ----
    private volatile int maxFps = 0;
    private long lastConvertTime = 0;

    // ---- face detection (hacker boxes) ----
    // The framework's android.media.FaceDetector is the smallest face
    // detection on Android: it is built into the OS since API 1, needs no
    // libraries and no RAM beyond a couple of RGB_565 bitmaps. The legacy
    // Camera API delivers sensor-raw NV21 frames, which the stream never
    // rotates, so a person can appear sideways to the detector (it only
    // matches horizontally-aligned eye pairs). To be posture-proof we scan
    // the frame as-is and rotated 90 degrees - search mode does both per
    // slot, tracking mode alternates to keep fps. Scale, rate, max faces,
    // contrast and the full-res probe are user-tunable in Settings.
    private static final int MAX_FACES_HARD = 8;
    private static final long DETECT_INTERVAL_MS_DEFAULT = 120;
    private volatile boolean faceDetectEnabled = false;
    private volatile int faceMaxFaces = 4;
    private volatile int faceFinestDiv = 2;
    private volatile long faceScanMs = DETECT_INTERVAL_MS_DEFAULT;
    private volatile float faceContrast = 1.35f;
    private volatile boolean faceDeepScan = true;
    private FaceDetector detector0;
    private FaceDetector detector90;
    private Bitmap bmp0;
    private Bitmap bmp90;
    private int dbW;
    private int dbH;
    private final FaceDetector.Face[] detectFaces =
            new FaceDetector.Face[MAX_FACES_HARD];
    private final PointF detectMid = new PointF();
    private final float[] pts = new float[8];
    private long lastDetectAt = -1;
    private long lastDetectLogAt = -1;
    private long detectIntervalMs = DETECT_INTERVAL_MS_DEFAULT;
    // boxes actually drawn, plus per-orientation results so the freshest
    // pass that found faces wins (orientations alternate per slot)
    private final Rect[] faceBoxes = new Rect[MAX_FACES_HARD];
    private final Rect[] boxes0 = new Rect[MAX_FACES_HARD];
    private final Rect[] boxes90 = new Rect[MAX_FACES_HARD];
    private int count0, count90, slot0 = -10, slot90 = -10;
    private int slotTick = 0;
    // scan levels: 0=1/4, 1=1/3, 2=1/2, 3=full; adapts between coarse and
    // the user's finest level (full only via the deep-scan probe)
    private int escLevel = 0;
    private int emptyRuns = 0;
    private int foundRuns = 0;
    private int probeTick = 0;
    // mild contrast boost applied only to the detection draw: the old
    // Neven engine needs strong edges; this fights glare, washed-out
    // prints and the JPEG generation loss in the pipeline
    private final Paint detectPaint = new Paint();
    private float paintContrast = -1f;
    // per-pass timing so we can tune scale/cost from logcat
    private long passCostSum = 0;
    private int passCostN = 0;
    private long passCostMax = 0;
    private long passCostStart = 0;
    private final Matrix map0 = new Matrix();
    private final Matrix inv0 = new Matrix();
    private final Matrix map90 = new Matrix();
    private final Matrix inv90 = new Matrix();
    private volatile int faceCount = 0;
    private Paint faceBorder;
    private Paint faceCorner;
    private Paint faceScan;

    public JpegFrames(Context context) {
        this.appContext = context != null
                ? context.getApplicationContext() : null;
        converter = new Thread(new Runnable() {
            @Override
            public void run() {
                convertLoop();
            }
        }, "cameragate-converter");
        converter.setDaemon(true);
        converter.start();
        for (int i = 0; i < MAX_FACES_HARD; i++) {
            faceBoxes[i] = new Rect();
            boxes0[i] = new Rect();
            boxes90[i] = new Rect();
        }
    }

    public void setOsdEnabled(boolean enabled) {
        this.osdEnabled = enabled;
    }

    public void setOsdLabel(String label) {
        this.osdLabel = label;
    }

    /** Turns green hacker-style face boxes on every frame on/off. */
    public void setFaceDetection(boolean enabled) {
        this.faceDetectEnabled = enabled;
        if (!enabled) {
            faceCount = 0;
        }
    }

    /**
     * Applies the tunable detection parameters from Settings (clamped):
     * max faces, finest scan divisor (4..1), scan throttle ms, contrast
     * gain (1.0 = off) and the full-res deep-scan probe.
     */
    public void applyFaceSettings(int maxFaces, int finestDiv,
                                  int scanMs, float contrast,
                                  boolean deepScan) {
        faceMaxFaces = maxFaces < 1 ? 1 : (maxFaces > MAX_FACES_HARD
                ? MAX_FACES_HARD : maxFaces);
        faceFinestDiv = finestDiv < 1 ? 1 : (finestDiv > 4 ? 4 : finestDiv);
        faceScanMs = scanMs < 60 ? 60 : (scanMs > 2000 ? 2000 : scanMs);
        faceContrast = contrast < 1f ? 1f : (contrast > 2f ? 2f : contrast);
        faceDeepScan = deepScan;
        if (faceCount > faceMaxFaces) {
            faceCount = faceMaxFaces;
        }
    }

    /** Number of faces found in the last detection run (0 when disabled). */
    public int faceCount() {
        return faceCount;
    }

    /** Caps the number of frames converted per second (0 = uncapped). */
    public void setMaxFps(int maxFps) {
        this.maxFps = maxFps < 0 ? 0 : maxFps;
    }

    /** Activates the pipeline. Can be called repeatedly (e.g. after a recording pause). */
    public synchronized void start(int w, int h) {
        if (w != width || h != height) {
            width = w;
            height = h;
            freeBuffers.clear();
            pending.clear();
            int frameBytes = w * h * 3 / 2;
            for (int i = 0; i < BUFFER_COUNT; i++) {
                freeBuffers.add(new byte[frameBytes]);
            }
        }
        running = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Pauses conversion (used while MediaRecorder owns the camera). */
    public synchronized void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Permanent teardown; the converter thread exits. */
    public void shutdown() {
        synchronized (this) {
            shutdown = true;
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public boolean isRunning() {
        synchronized (this) {
            return running;
        }
    }

    public synchronized byte[] acquireBuffer() {
        byte[] buf = freeBuffers.poll();
        if (buf == null) {
            return new byte[width * height * 3 / 2];
        }
        return buf;
    }

    /** Called from the camera preview thread with a freshly filled buffer. */
    public void post(byte[] data) {
        synchronized (this) {
            if (!running) {
                freeBuffers.add(data);
                return;
            }
            if (pending.size() >= QUEUE_CAPACITY) {
                freeBuffers.add(pending.remove());
            }
            pending.add(data);
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void convertLoop() {
        while (true) {
            boolean have = false;
            byte[] raw = null;
            synchronized (this) {
                if (shutdown) {
                    break;
                }
                if (running && !pending.isEmpty()) {
                    raw = pending.remove();
                    have = true;
                }
            }
            if (have) {
                if (maxFps > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastConvertTime < 1000L / maxFps) {
                        // over the cap: drop the frame, keep the buffer
                        synchronized (this) {
                            freeBuffers.add(raw);
                        }
                        continue;
                    }
                    lastConvertTime = now;
                }
                convert(raw);
                synchronized (this) {
                    freeBuffers.add(raw);
                }
            } else {
                synchronized (lock) {
                    try {
                        lock.wait(200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    private void convert(byte[] nv21) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 2);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        if (!yuv.compressToJpeg(new Rect(0, 0, width, height), 72, out)) {
            return;
        }
        byte[] jpeg = out.toByteArray();
        if (osdEnabled || faceDetectEnabled) {
            jpeg = overlayOsd(jpeg);
        }
        synchronized (lock) {
            latestJpeg = jpeg;
            latestSeq++;
            lock.notifyAll();
        }
        final Listener l;
        synchronized (this) {
            l = listener;
        }
        if (l != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    l.onNewFrame();
                }
            });
        }
    }

    /** Blocks up to timeoutMs for a newer JPEG than [current]; returns the newest generation. */
    public long awaitNewer(long current, long timeoutMs) {
        synchronized (lock) {
            long t0 = System.currentTimeMillis();
            while (latestSeq == current) {
                long now = System.currentTimeMillis();
                if (now - t0 >= timeoutMs) {
                    break;
                }
                try {
                    lock.wait(timeoutMs - (now - t0));
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            return latestSeq;
        }
    }

    public byte[] latest() {
        synchronized (lock) {
            return latestJpeg;
        }
    }

    public long latestSeq() {
        synchronized (lock) {
            return latestSeq;
        }
    }

    public void setListener(Listener l) {
        synchronized (this) {
            listener = l;
        }
    }

    // ------------------------------------------------------------- OSD

    /**
     * Decodes the frame, draws the green status block (date/time, fps,
     * battery, server label) into the bottom-left corner, re-encodes.
     * Returns the original JPEG when anything fails so the stream never
     * dies because of the overlay.
     */
    private byte[] overlayOsd(byte[] jpeg) {
        // Decode straight into a reused mutable bitmap (inBitmap, API 11+):
        // no per-frame allocation, no copy, one Canvas. Keep the canvas in
        // RGB_565 - the frame has no alpha and 565 is the cheapest encode.
        if (osdCanvas == null || osdCanvas.getWidth() != width
                || osdCanvas.getHeight() != height) {
            recycleOsdCanvas();
            osdCanvas = Bitmap.createBitmap(width, height,
                    Bitmap.Config.RGB_565);
            osdOpts = new BitmapFactory.Options();
            osdOpts.inPreferredConfig = Bitmap.Config.RGB_565;
            osdOpts.inBitmap = osdCanvas;
        }
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeByteArray(
                    jpeg, 0, jpeg.length, osdOpts);
        } catch (IllegalArgumentException sizeMismatch) {
            // inBitmap race or size change: rebuild once, drop the frame's
            // overlay if that fails so the stream never dies
            recycleOsdCanvas();
            osdCanvas = Bitmap.createBitmap(width, height,
                    Bitmap.Config.RGB_565);
            osdOpts = new BitmapFactory.Options();
            osdOpts.inPreferredConfig = Bitmap.Config.RGB_565;
            osdOpts.inBitmap = osdCanvas;
            bmp = null;
        }
        if (bmp == null) {
            return jpeg;
        }
        ensurePaints();
        float ts = Math.max(12f, height / 28f);
        osdFill.setTextSize(ts);
        osdStroke.setTextSize(ts);

        tickFps();
        String line1;
        synchronized (osdTime) {
            line1 = osdTime.format(new Date());
        }
        StringBuilder line2 = new StringBuilder(64);
        line2.append(fps).append(" FPS   BAT ").append(batteryPercent()).append('%');
        if (faceDetectEnabled) {
            line2.append("   FACES ").append(faceCount);
        }
        if (osdLabel != null) {
            line2.append("   ").append(osdLabel);
        }
        String s2 = line2.toString();
        String s3 = systemLine();
        String s4 = gateLine();

        Canvas c = new Canvas(bmp);
        if (osdEnabled) {
            float x = ts * 0.5f;
            float y1 = height - ts * 0.4f;
            float y2 = y1 - ts * 1.35f;
            float y3 = y2 - ts * 1.35f;
            float y4 = y3 - ts * 1.35f;
            c.drawText(line1, x, y1, osdStroke);
            c.drawText(line1, x, y1, osdFill);
            c.drawText(s2, x, y2, osdStroke);
            c.drawText(s2, x, y2, osdFill);
            c.drawText(s3, x, y3, osdStroke);
            c.drawText(s3, x, y3, osdFill);
            c.drawText(s4, x, y4, osdStroke);
            c.drawText(s4, x, y4, osdFill);
        }

        if (faceDetectEnabled) {
            detectFacesIn(bmp);
            drawFaceBoxes(c);
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 1024);
            if (bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)) {
                jpeg = out.toByteArray();
            }
        } catch (OutOfMemoryError ignored) {
            // serve the unembellished frame
        }
        return jpeg;
    }

    // ------------------------------------------------------------- faces

    /**
     * Runs the framework FaceDetector on small scaled copies of the frame
     * (reused bitmaps only, no per-frame allocation) and stores the scaled
     * full-resolution boxes. The legacy Camera API stream is never rotated,
     * so a person can appear sideways: orientations 0 and 90 are scanned on
     * alternating slots, and the freshest orientation that found faces
     * wins. Scans adapt between 1/4 and the user's finest scale (1/2 by
     * default) so the old Neven detector (it needs >= 20px of eye distance
     * in the analyzed image) works for faces that are merely close, not
     * huge. Throttled so the oldest phones never burn a full core; between
     * runs, the last boxes keep being drawn.
     */
    private void detectFacesIn(Bitmap full) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDetectAt < detectIntervalMs) {
            return;
        }
        // settle the throttle: settings are applied live, not only on start
        float interval = detectIntervalMs;
        long want = faceScanMs;
        if (interval != want) {
            detectIntervalMs = want;
        }
        lastDetectAt = now;
        // even dimensions: the native FFT detector requires an even width
        int maxLevel = faceFinestDiv == 1 ? 3
                : (faceFinestDiv == 2 ? 2 : (faceFinestDiv == 3 ? 1 : 0));
        maxLevel = Math.min(3, maxLevel + (faceDeepScan ? 1 : 0));
        int div = escLevel == 0 ? 4 : (escLevel == 1 ? 3
                : (escLevel == 2 ? 2 : 1));
        int dw = Math.max(96, width / div) & ~1;
        int dh = Math.max(96, height * dw / width) & ~1;
        try {
            boolean tracking = faceCount > 0 && emptyRuns < 3;
            // search mode (both orientations) runs at the user's finest
            // level; coarse levels only alternate to save CPU
            boolean fine = escLevel >= maxLevel - (faceDeepScan ? 1 : 0)
                    && emptyRuns < 12;
            boolean fullProbe = faceDeepScan && escLevel == maxLevel
                    && emptyRuns >= 12 && (probeTick++ & 7) == 0;
            if (fullProbe) {
                // nothing found for a while: occasionally re-acquire at
                // full resolution to catch smaller faces
                dw = Math.max(128, width) & ~1;
                dh = Math.max(128, height) & ~1;
            }
            if (bmp0 == null || dbW != dw || dbH != dh) {
                if (bmp0 != null) {
                    bmp0.recycle();
                    bmp90.recycle();
                }
                dbW = dw;
                dbH = dh;
                bmp0 = Bitmap.createBitmap(dw, dh, Bitmap.Config.RGB_565);
                bmp90 = Bitmap.createBitmap(dh, dw, Bitmap.Config.RGB_565);
                // WARNING: Android's actual framework constructor signature
                // is FaceDetector(width, height, maxFaces), not the
                // (maxFaces, width, height) shown in some API docs. All
                // three params are int, so a swapped call compiles fine but
                // throws "bitmap size doesn't match initialization" at
                // runtime (observed on Android 4.3). The face width must
                // also be even.
                detector0 = new FaceDetector(dw, dh, faceMaxFaces);
                detector90 = new FaceDetector(dh, dw, faceMaxFaces);
                Log.i(TAG, "detector init: frame=" + width + "x" + height
                        + " pass0=" + dw + "x" + dh
                        + " pass90=" + dh + "x" + dw);
                float k = width > 0 ? (float) dw / width : 1f;
                map0.setScale(k, k);
                map0.invert(inv0);
                // 90-degree CW pixel rotation of a WxH source into an HxW
                // target: (X, Y) -> (H - 1 - Y, X), then scaled by k.
                map90.setValues(new float[]{0, -1, height - 1,
                        1, 0, 0, 0, 0, 1});
                map90.postScale(k, k);
                map90.invert(inv90);
            }
            ensureDetectPaint();
            passCostStart = SystemClock.uptimeMillis();
            boolean hunting = faceCount > 0 && faceCount < faceMaxFaces;
            if ((fine && !fullProbe && !tracking) || hunting) {
                // search/hunt mode: both orientations every slot so a face
                // only the other rotation would catch is not missed while
                // we still have room for more boxes
                int c0 = runPass(detector0, bmp0, full, map0, inv0, boxes0);
                int c90 = runPass(detector90, bmp90, full, map90, inv90, boxes90);
                count0 = c0;
                count90 = c90;
                slot0 = ++slotTick;
                slot90 = ++slotTick;
            } else {
                boolean rot = (slotTick++ & 1) != 0;
                int n = runPass(rot ? detector90 : detector0,
                        rot ? bmp90 : bmp0, full,
                        rot ? map90 : map0, rot ? inv90 : inv0,
                        rot ? boxes90 : boxes0);
                if (rot) {
                    count90 = n;
                    slot90 = slotTick;
                } else {
                    count0 = n;
                    slot0 = slotTick;
                }
            }
            // merge both orientations' fresh boxes instead of winner-take-all:
            // a face only the 90deg pass caught must not vanish just because
            // the 0deg pass found nothing (or vice versa) - that is what made
            // a second face in a different pose drop out
            int shownC0 = (count0 > 0 && slotTick - slot0 <= 3) ? count0 : 0;
            int shownC90 = (count90 > 0 && slotTick - slot90 <= 3)
                    ? count90 : 0;
            int count = 0;
            Rect[] a = boxes0;
            Rect[] b = boxes90;
            int na = shownC0;
            int nb = shownC90;
            for (int s = 0; s < na + nb && count < faceMaxFaces; s++) {
                Rect r = s < na ? a[s] : b[s - na];
                boolean dup = false;
                for (int i = 0; i < count && !dup; i++) {
                    dup = overlaps(r, faceBoxes[i]);
                }
                if (!dup) {
                    faceBoxes[count++].set(r);
                }
            }
            faceCount = count;
            if (count > 0) {
                emptyRuns = 0;
                if (++foundRuns >= 10 && escLevel > 0) {
                    // steady detections at a finer scale: try coarser
                    // again to save CPU
                    escLevel--;
                    foundRuns = 0;
                }
            } else {
                foundRuns = 0;
                if (++emptyRuns >= 4 && escLevel < maxLevel) {
                    // nothing found: scan progressively finer
                    escLevel++;
                    emptyRuns = 0;
                }
            }
            if (now - lastDetectLogAt > 3000) {
                lastDetectLogAt = now;
                long avg = passCostN > 0 ? passCostSum / passCostN : 0;
                Log.i(TAG, "face detect: kept " + count + " (0deg=" + count0
                        + ", 90deg=" + count90 + ") on " + dw + "x" + dh
                        + " scale=1/" + div + (fullProbe ? " FULL" : "")
                        + " passAvg=" + avg + "ms max=" + passCostMax + "ms");
                passCostSum = 0;
                passCostN = 0;
                passCostMax = 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "face detection failed", e);
            faceCount = 0;
        }
    }

    /**
     * True when a and b cover mostly the same region - the same face seen
     * by both the 0deg and the 90deg pass, which should be counted once.
     */
    private boolean overlaps(Rect a, Rect b) {
        int iw = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        if (iw <= 0) {
            return false;
        }
        int ih = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (ih <= 0) {
            return false;
        }
        int inter = iw * ih;
        int area = Math.min(a.width() * a.height(),
                b.width() * b.height());
        return area > 0 && inter * 2 > area;
    }

    /**
     * One detector pass: draws the full-res frame transformed by [map] into
     * the small [target] bitmap, runs the detector, maps any face boxes back
     * into full-resolution coordinates via [inv] and stores them in [out].
     * Returns the number of faces found.
     */
    private int runPass(FaceDetector detector, Bitmap target, Bitmap full,
                        Matrix map, Matrix inv, Rect[] out) {
        Canvas dc = new Canvas(target);
        dc.drawBitmap(full, map, detectPaint);
        long t0 = SystemClock.uptimeMillis();
        int n;
        try {
            n = detector.findFaces(target, detectFaces);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "findFaces dims: target=" + target.getWidth() + "x"
                    + target.getHeight(), e);
            throw e;
        }
        long dt = SystemClock.uptimeMillis() - t0;
        passCostSum += dt;
        passCostN++;
        if (dt > passCostMax) {
            passCostMax = dt;
        }
        n = Math.min(n, faceMaxFaces);
        int count = 0;
        for (int i = 0; i < n; i++) {
            FaceDetector.Face f = detectFaces[i];
            if (f == null) {
                continue;
            }
            f.getMidPoint(detectMid);
            float ew = f.eyesDistance();
            // four box corners in detection space: width ~2.8x, height ~3.4x
            // the eye separation, centered on the eye midpoint
            pts[0] = detectMid.x - ew * 1.4f;
            pts[1] = detectMid.y - ew * 1.7f;
            pts[2] = detectMid.x + ew * 1.4f;
            pts[3] = detectMid.y - ew * 1.7f;
            pts[4] = detectMid.x - ew * 1.4f;
            pts[5] = detectMid.y + ew * 1.7f;
            pts[6] = detectMid.x + ew * 1.4f;
            pts[7] = detectMid.y + ew * 1.7f;
            inv.mapPoints(pts);
            int x1 = (int) Math.min(Math.min(pts[0], pts[2]),
                    Math.min(pts[4], pts[6]));
            int y1 = (int) Math.min(Math.min(pts[1], pts[3]),
                    Math.min(pts[5], pts[7]));
            int x2 = (int) Math.max(Math.max(pts[0], pts[2]),
                    Math.max(pts[4], pts[6]));
            int y2 = (int) Math.max(Math.max(pts[1], pts[3]),
                    Math.max(pts[5], pts[7]));
            out[count].set(x1, y1, x2, y2);
            count++;
        }
        return count;
    }

    /**
     * Hacker-movie targeting boxes: faint full border, bright corner
     * brackets, two sweeping scan lines inside the box and a monospace
     * "TARGET n" label. All paints are pre-allocated; the only per-frame
     * allocations are the small label strings.
     */
    private void drawFaceBoxes(Canvas c) {
        int n = faceCount;
        if (n <= 0) {
            return;
        }
        ensureFacePaints();
        long sweep = SystemClock.uptimeMillis() / 50;
        long sweep2 = SystemClock.uptimeMillis() / 37;
        for (int i = 0; i < n; i++) {
            Rect r = faceBoxes[i];
            if (r.width() <= 0 || r.height() <= 0) {
                continue;
            }
            int x1 = r.left;
            int y1 = r.top;
            int x2 = r.right;
            int y2 = r.bottom;
            int corner = Math.max(12, Math.min(r.width() / 4, r.height() / 4));

            c.drawRect(r, faceBorder);

            int t = Math.min(corner, 24);
            int cl = Math.min(corner, 40);
            c.drawLine(x1, y1 + t, x1, y1, faceCorner);
            c.drawLine(x1, y1, x1 + cl, y1, faceCorner);
            c.drawLine(x2, y1 + t, x2, y1, faceCorner);
            c.drawLine(x2, y1, x2 - cl, y1, faceCorner);
            c.drawLine(x1, y2 - t, x1, y2, faceCorner);
            c.drawLine(x1, y2, x1 + cl, y2, faceCorner);
            c.drawLine(x2, y2 - t, x2, y2, faceCorner);
            c.drawLine(x2, y2, x2 - cl, y2, faceCorner);

            int span = Math.max(1, r.height());
            int sy = y1 + (int) (sweep % span);
            int sy2 = y1 + (int) ((sweep2 + i * 97) % span);
            c.drawLine(x1 + 2, sy, x2 - 2, sy, faceScan);
            c.drawLine(x1 + 2, sy2, x2 - 2, sy2, faceScan);

            String label = String.format(Locale.US, "TARGET %02d", i + 1);
            float ts = Math.max(10f, Math.min(20f, r.height() * 0.15f));
            osdFill.setTextSize(ts);
            osdStroke.setTextSize(ts);
            float lx = x1;
            float ly = y1 - ts * 0.3f;
            if (ly < ts) {
                ly = y1 + ts * 1.3f;
            }
            c.drawText(label, lx, ly, osdStroke);
            c.drawText(label, lx, ly, osdFill);
        }
    }

    private void ensureDetectPaint() {
        if (paintContrast != faceContrast) {
            float g = faceContrast;
            float o = (1f - g) * 255f / 2f * 2f / g; // keep mid-brightness
            detectPaint.setColorFilter(new ColorMatrixColorFilter(
                    new float[]{
                            g, 0, 0, 0, o,
                            0, g, 0, 0, o,
                            0, 0, g, 0, o,
                            0, 0, 0, 1, 0}));
            paintContrast = faceContrast;
        }
    }

    private void ensureFacePaints() {
        if (faceCorner == null) {
            faceBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
            faceBorder.setColor(0x3300FF88);
            faceBorder.setStyle(Paint.Style.STROKE);
            faceBorder.setStrokeWidth(1.5f);

            faceCorner = new Paint(Paint.ANTI_ALIAS_FLAG);
            faceCorner.setColor(0xFF00FF88);
            faceCorner.setStyle(Paint.Style.STROKE);
            faceCorner.setStrokeCap(Paint.Cap.ROUND);
            faceCorner.setStrokeWidth(Math.max(2.5f, height / 240f));

            faceScan = new Paint(Paint.ANTI_ALIAS_FLAG);
            faceScan.setColor(0x6600FF88);
            faceScan.setStyle(Paint.Style.STROKE);
            faceScan.setStrokeWidth(1f);
        }
    }

    private void recycleOsdCanvas() {
        if (osdCanvas != null) {
            osdCanvas.recycle();
            osdCanvas = null;
        }
        osdOpts = null;
    }

    private void ensurePaints() {
        if (osdFill == null) {
            osdFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            osdFill.setColor(0xFF00FF88);
            osdFill.setTypeface(Typeface.MONOSPACE);
            osdFill.setStyle(Paint.Style.FILL);

            osdStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            osdStroke.setColor(0xCC000000);
            osdStroke.setTypeface(Typeface.MONOSPACE);
            osdStroke.setStyle(Paint.Style.STROKE);
            osdStroke.setStrokeWidth(tsWidth());
        }
    }

    private float tsWidth() {
        return Math.max(2f, (Math.max(12f, height / 28f)) / 7f);
    }

    private void tickFps() {
        long now = System.currentTimeMillis();
        if (fpsWindowStart < 0) {
            fpsWindowStart = now;
            fpsCount = 1;
            return;
        }
        fpsCount++;
        long span = now - fpsWindowStart;
        if (span >= 1000) {
            fps = (int) Math.round(fpsCount * 1000.0 / span);
            fpsWindowStart = now;
            fpsCount = 0;
        }
    }

    /** Battery level in percent via the sticky battery broadcast, or "?". */
    private String batteryPercent() {
        if (appContext == null) {
            return "?";
        }
        try {
            Intent b = appContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) {
                return "?";
            }
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) {
                return "?";
            }
            return String.valueOf(level * 100 / scale);
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Hacker reads of /proc/loadavg + /proc/meminfo, uptime and RAM,
     * cached for 1 second: "UP 01:23:45 · LOAD 0.4 0.6 0.7 · RAM 184/862MB".
     */
    private String systemLine() {
        long now = SystemClock.uptimeMillis();
        if (osdStats != null && now - statsAt < 1000) {
            return osdStats;
        }
        statsAt = now;
        try {
            StringBuilder b = new StringBuilder(48);
            long s = now / 1000;
            b.append("UP ").append(String.format(Locale.US, "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60));
            BufferedReader r = new BufferedReader(
                    new FileReader("/proc/loadavg"));
            String line = r.readLine();
            r.close();
            String[] p = line.split("\\s+");
            b.append("  LOAD ").append(p[0]).append(' ')
                    .append(p[1]).append(' ').append(p[2]);
            if (appContext != null) {
                ActivityManager am = (ActivityManager) appContext
                        .getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi =
                        new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                b.append("  RAM ").append(mi.availMem / (1024 * 1024))
                        .append('/').append(mi.totalMem / (1024 * 1024))
                        .append("MB");
            }
            osdStats = b.toString();
        } catch (Exception e) {
            osdStats = "UP ?  LOAD ?";
        }
        return osdStats;
    }

    /**
     * Static banner, rebuilt only when the frame size changes:
     * "CAMERAGATE 0.0.1 · D2302 · 1280x720".
     */
    private String gateLine() {
        if (osdMeta == null || metaW != width || metaH != height) {
            metaW = width;
            metaH = height;
            osdMeta = "CAMERAGATE " + BuildConfig.VERSION_NAME
                    + "  " + Build.MODEL + "  " + width + "x" + height;
        }
        return osdMeta;
    }
}