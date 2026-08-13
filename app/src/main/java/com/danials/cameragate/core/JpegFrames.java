package com.danials.cameragate.core;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
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
    }

    public void setOsdEnabled(boolean enabled) {
        this.osdEnabled = enabled;
    }

    public void setOsdLabel(String label) {
        this.osdLabel = label;
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
        if (osdEnabled) {
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
        if (osdLabel != null) {
            line2.append("   ").append(osdLabel);
        }
        String s2 = line2.toString();
        String s3 = systemLine();
        String s4 = gateLine();

        Canvas c = new Canvas(bmp);
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