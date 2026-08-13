package com.danials.cameragate.core.face;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Multi-scale face detector built on top of a single {@link Cascade},
 * mirroring OpenCV's detectMultiScale pipeline:
 *
 * <ol>
 *   <li>Blow up a scale pyramid (1.0, 1/1.2, 1/1.2^2, ...) by bilinearly
 *       downscaling the grayscale image while the window still fits;</li>
 *   <li>At each layer, run the cascade over every window position stepping
 *       by half the window size — the same dense scan OpenCV does — so a
 *       face appears in several overlapping copies;</li>
 *   <li>Group overlapping detections (eps = 0.2, min neighbors = 3) into
 *       one box per face, weighted mean of the group, same as OpenCV's
 *       groupRectangles; detections are emitted in original-image
 *       coordinates.</li>
 * </ol>
 *
 * <p>No Android dependencies: pure Java, so it runs unchanged in host-side
 * tests and on API 16 devices.
 */
public final class FaceDetector {

    private static final double SCALE_FACTOR = 1.2;
    private static final int MIN_NEIGHBORS = 3;
    private static final double GROUP_EPS = 0.2;
    private static final int MAX_CANDIDATES = 4096;

    // shared daemon pool for the per-layer window scan; the pool is small
    // (up to 4 workers) so Dalvik keeps all cores warm on the phone
    private static final int MAX_WORKERS = 4;
    private static ExecutorService scanPool;

    private static synchronized ExecutorService pool() {
        if (scanPool == null) {
            int n = Math.min(MAX_WORKERS,
                    Math.max(1, Runtime.getRuntime().availableProcessors()));
            ThreadFactory daemon = new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "face-scan");
                    t.setDaemon(true);
                    return t;
                }
            };
            scanPool = Executors.newFixedThreadPool(n, daemon);
        }
        return scanPool;
    }

    /**
     * One row-range of one scale layer. Ranges are defined by index into
     * the original wy sequence (wy = idx * 2), so each task scans exactly
     * the same odd/even alignment the single-threaded loop would.
     */
    private static final class RangeScan implements Callable<List<IntRect>> {
        final Cascade cascade;
        final IntegralImage ig;
        final int yFrom;
        final int yTo;
        final int layerW;
        final double factor;
        final int winW;
        final int winH;
        final long[] winCount;

        RangeScan(Cascade cascade, IntegralImage ig, int yFrom, int yTo,
                  int layerW, double factor, int winW, int winH,
                  long[] winCount) {
            this.cascade = cascade;
            this.ig = ig;
            this.yFrom = yFrom;
            this.yTo = yTo;
            this.layerW = layerW;
            this.factor = factor;
            this.winW = winW;
            this.winH = winH;
            this.winCount = winCount;
        }

        public List<IntRect> call() {
            List<IntRect> found = new ArrayList<IntRect>(4);
            int step = 2;
            long cnt = 0;
            for (int wy = yFrom; wy < yTo; wy += step) {
                for (int wx = 0; wx + cascade.winW <= layerW; wx += step) {
                    cnt++;
                    int res = cascade.run(ig, wx, wy);
                    if (res > 0) {
                        found.add(new IntRect(
                                (int) Math.round(wx * factor),
                                (int) Math.round(wy * factor),
                                winW, winH));
                    } else if (res == -1) {
                        // OpenCV's invoker skips one step after a first-
                        // stage rejection (result == 0 there): same grid
                        // coverage for less work
                        wx += step;
                    }
                }
            }
            synchronized (winCount) {
                winCount[0] += cnt;
            }
            return found;
        }
    }

    private final Cascade cascade;

    // pooled per-detect buffers (single-threaded use in the app)
    private final List<IntRect> candidates = new ArrayList<IntRect>(256);

    // pyramid cache: layer geometry + reused buffers for the current
    // input size, so repeated scans allocate nothing per pass
    private int cacheW = -1;
    private int cacheH = -1;
    private int nLayers;
    private double[] layerFactor;
    private int[] lWinW;
    private int[] lWinH;
    private int[] lLayerW;
    private int[] lLayerH;
    private byte[][] lBuf;
    private IntegralImage[] lIg;

    // phase timers (ms) accumulated per detect call; read via the getters
    private long lastPrepMs = 0;
    private long lastScanMs = 0;
    private long lastLayerCount = 0;
    private long lastWindowCount = 0;

    public FaceDetector(Cascade cascade) {
        this.cascade = cascade;
    }

    /** True when at least one analysis window fits in the image. */
    public boolean supports(int w, int h) {
        return w >= cascade.winW && h >= cascade.winH;
    }

    /**
     * Detects faces in a grayscale image. Appends up to {@code maxFaces}
     * boxes to {@code out} (cleared first); returns the count. Detections
     * are full-res scan coordinates in {@code gray} pixel space. Uses the
     * default grouping strictness of {@code MIN_NEIGHBORS}.
     */
    public int detect(byte[] gray, int w, int h, int maxFaces,
                      List<IntRect> out) {
        return detect(gray, w, h, maxFaces, MIN_NEIGHBORS, out);
    }

    /**
     * Detects faces in a grayscale image. Appends up to {@code maxFaces}
     * boxes to {@code out} (cleared first); returns the count. Detections
     * are full-res scan coordinates in {@code gray} pixel space.
     *
     * <p>The scale loop mirrors OpenCV's detectMultiScaleNoGrouping: the
     * factor starts at 1 and multiplies by {@link #SCALE_FACTOR} while the
     * reported window (20 * factor) still fits the frame; each layer is the
     * frame downscaled by 1/factor and the analysis window stays 20x20,
     * scanned in 2px steps (a deliberate decimation on top of OpenCV's
     * 1px-at-large-scales: it costs negligible sensitivity for a fraction
     * of the CPU on low-end devices).
     */
    public int detect(byte[] gray, int w, int h, int maxFaces,
                      int minNeighbors, List<IntRect> out) {
        out.clear();
        if (maxFaces <= 0 || !supports(w, h)) {
            return 0;
        }
        candidates.clear();
        buildCache(w, h);
        long prepMs = 0;
        long scanMs = 0;
        long windows = 0;
        // prep phase is serial: pyramid downscales, integral refills and
        // the per-stride feature offsets are all ready before the workers
        // touch the cascade
        for (int li = 0; li < nLayers; li++) {
            long tPre = System.nanoTime();
            byte[] buf = lBuf[li];
            if (buf == null) {
                lIg[li].refill(gray, w, h);
            } else {
                downscale(gray, w, h, lLayerW[li], lLayerH[li],
                        layerFactor[li], buf);
                lIg[li].refill(buf, lLayerW[li], lLayerH[li]);
            }
            cascade.prepareOffsets(lIg[li].stride);
            prepMs += (System.nanoTime() - tPre) / 1000000;
        }
        // scan phase splits each layer's wy sequence into index ranges so
        // every worker sees exactly the windows the single-threaded loop
        // would, and merging in task order keeps the candidate order -
        // and therefore the grouped output - byte-identical
        int workers = pool() == null ? 1 : Math.min(MAX_WORKERS,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService ex = pool();
        long[] winCount = new long[1];
        List<Callable<List<IntRect>>> tasks =
                new ArrayList<Callable<List<IntRect>>>(nLayers * 2 + 1);
        for (int li = 0; li < nLayers; li++) {
            int layerW = lLayerW[li];
            int layerH = lLayerH[li];
            int wyCount = (layerH - cascade.winH) / 2 + 1;
            if (wyCount <= 0) {
                continue;
            }
            if (workers <= 1 || wyCount <= 16) {
                tasks.add(new RangeScan(cascade, lIg[li], 0,
                        wyCount * 2, layerW, layerFactor[li],
                        lWinW[li], lWinH[li], winCount));
                continue;
            }
            int nT = Math.min(workers, wyCount);
            for (int i = 0; i < nT; i++) {
                int idxFrom = i * wyCount / nT;
                int idxTo = i == nT - 1 ? wyCount : (i + 1) * wyCount / nT;
                if (idxTo <= idxFrom) {
                    continue;
                }
                tasks.add(new RangeScan(cascade, lIg[li], idxFrom * 2,
                        idxTo * 2, layerW, layerFactor[li],
                        lWinW[li], lWinH[li], winCount));
            }
        }
        long tScan0 = System.nanoTime();
        if (tasks.isEmpty()) {
            scanMs = 0;
        } else {
            try {
                List<Future<List<IntRect>>> done = ex.invokeAll(tasks);
                int cap = MAX_CANDIDATES;
                for (int t = 0; t < done.size() && candidates.size() < cap;
                        t++) {
                    List<IntRect> part = done.get(t).get();
                    for (int i = 0, n = part.size();
                            i < n && candidates.size() < cap; i++) {
                        candidates.add(part.get(i));
                    }
                }
            } catch (Exception e) {
                // degrade to whatever was gathered; detection is best-effort
                scanMs = (System.nanoTime() - tScan0) / 1000000;
                lastPrepMs = prepMs;
                lastScanMs = scanMs;
                lastLayerCount = nLayers;
                lastWindowCount = windows;
                group(candidates, maxFaces, minNeighbors, out);
                return out.size();
            }
            scanMs = (System.nanoTime() - tScan0) / 1000000;
        }
        windows = winCount[0];
        lastPrepMs = prepMs;
        lastScanMs = scanMs;
        lastLayerCount = nLayers;
        lastWindowCount = windows;
        group(candidates, maxFaces, minNeighbors, out);
        return out.size();
    }

    /**
     * (Re)builds the cached pyramid geometry and buffers for the given
     * input size. Mirror of the original scale loop: factor starts at 1
     * and multiplies by {@link #SCALE_FACTOR} while the reported window
     * still fits; each layer is the frame downscaled by 1/factor.
     */
    private void buildCache(int w, int h) {
        if (w == cacheW && h == cacheH) {
            return;
        }
        cacheW = w;
        cacheH = h;
        int maxLayers = 32;
        layerFactor = new double[maxLayers];
        lWinW = new int[maxLayers];
        lWinH = new int[maxLayers];
        lLayerW = new int[maxLayers];
        lLayerH = new int[maxLayers];
        nLayers = 0;
        double factor = 1.0;
        while (nLayers < maxLayers) {
            int winW = (int) Math.round(cascade.winW * factor);
            int winH = (int) Math.round(cascade.winH * factor);
            if (winW > w || winH > h) {
                break;
            }
            layerFactor[nLayers] = factor;
            lWinW[nLayers] = winW;
            lWinH[nLayers] = winH;
            lLayerW[nLayers] = (int) Math.round(w / factor);
            lLayerH[nLayers] = (int) Math.round(h / factor);
            nLayers++;
            factor *= SCALE_FACTOR;
        }
        lBuf = new byte[nLayers][];
        lIg = new IntegralImage[nLayers];
        for (int i = 1; i < nLayers; i++) {
            lBuf[i] = new byte[lLayerW[i] * lLayerH[i]];
            lIg[i] = IntegralImage.compute(lBuf[i], lLayerW[i], lLayerH[i],
                    !cascade.lbp, false);
        }
        lIg[0] = IntegralImage.compute(new byte[w * h], w, h,
                !cascade.lbp, false);
    }

    /** Pyramid-prep milliseconds of the last detect call. */
    public long lastPrepMs() {
        return lastPrepMs;
    }

    /** Window-scan milliseconds of the last detect call. */
    public long lastScanMs() {
        return lastScanMs;
    }

    /** Number of scale layers scanned in the last detect call. */
    public long lastLayerCount() {
        return lastLayerCount;
    }

    /** Number of windows evaluated in the last detect call. */
    public long lastWindowCount() {
        return lastWindowCount;
    }

    // ------------------------------------------------ grouping

    /**
     * Weighted-mean grouping of overlapping boxes (OpenCV's classic
     * groupRectangles): a candidate survives when at least
     * {@code minNeighbors - 1} boxes overlap it by more than eps of the
     * smaller area; the merged box is the overlap-weighted mean of its
     * group. Surplus boxes are dropped by area (largest first).
     */
    private void group(List<IntRect> cands, int maxFaces, int minNeighbors,
                       List<IntRect> out) {
        int n = cands.size();
        if (n == 0) {
            return;
        }
        for (int i = 0; i < n && out.size() < maxFaces; i++) {
            IntRect ri = cands.get(i);
            long sumX = ri.x, sumY = ri.y, sumW = ri.width, sumH = ri.height;
            long count = 1;
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                IntRect rj = cands.get(j);
                if (overlap(ri, rj)) {
                    sumX += rj.x;
                    sumY += rj.y;
                    sumW += rj.width;
                    sumH += rj.height;
                    count++;
                }
            }
            if (count >= minNeighbors) {
                IntRect m = new IntRect((int) (sumX / count),
                        (int) (sumY / count), (int) (sumW / count),
                        (int) (sumH / count));
                boolean dup = false;
                for (int k = 0; k < out.size(); k++) {
                    if (out.get(k).intersect(m).area() * 2
                            > Math.min(out.get(k).area(), m.area())) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(m);
                }
            }
        }
    }

    private static boolean overlap(IntRect a, IntRect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        if (x2 <= x1 || y2 <= y1) {
            return false;
        }
        long inter = (long) (x2 - x1) * (y2 - y1);
        long minArea = Math.min((long) a.width * a.height,
                (long) b.width * b.height);
        return inter > GROUP_EPS * minArea;
    }

    // ------------------------------------------------- scaling

    /**
     * Bilinear downscale into {@code out} (OpenCV's INTER_LINEAR_EXACT:
     * source coordinate (dst + 0.5) * scale - 0.5, floor + fraction,
     * nearest-neighbor rounding into a byte).
     */
    private void downscale(byte[] src, int sw, int sh, int dw, int dh,
                           double factor, byte[] out) {
        for (int y = 0; y < dh; y++) {
            float fy = (float) ((y + 0.5) * factor - 0.5);
            if (fy < 0) {
                fy = 0;
            }
            int fy0 = (int) fy;
            float ky = fy - fy0;
            int r0 = fy0 * sw;
            int r1 = Math.min(fy0 + 1, sh - 1) * sw;
            for (int x = 0; x < dw; x++) {
                float fx = (float) ((x + 0.5) * factor - 0.5);
                if (fx < 0) {
                    fx = 0;
                }
                int fx0 = (int) fx;
                float kx = fx - fx0;
                int x1 = Math.min(fx0 + 1, sw - 1);
                int a = src[r0 + fx0] & 0xFF;
                int b = src[r0 + x1] & 0xFF;
                int c = src[r1 + fx0] & 0xFF;
                int d = src[r1 + x1] & 0xFF;
                float top = a + (b - a) * kx;
                float bot = c + (d - c) * kx;
                out[y * dw + x] = (byte) (int) (top + (bot - top) * ky + 0.5f);
            }
        }
    }
}