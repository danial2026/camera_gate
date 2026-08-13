package com.danials.cameragate.core.face;

import java.util.ArrayList;
import java.util.List;

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

    private static final float SCALE_FACTOR = 1.2f;
    private static final int MIN_NEIGHBORS = 3;
    private static final double GROUP_EPS = 0.2;
    private static final int MAX_CANDIDATES = 4096;

    private final Cascade cascade;

    // pooled per-detect buffers (single-threaded use in the app)
    private final List<IntRect> candidates = new ArrayList<IntRect>(256);
    private byte[] scaledBuf;

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
        double factor = 1.0;
        while (candidates.size() < MAX_CANDIDATES) {
            int winW = (int) Math.round(cascade.winW * factor);
            int winH = (int) Math.round(cascade.winH * factor);
            if (winW > w || winH > h) {
                break;
            }
            int layerW = (int) Math.round(w / factor);
            int layerH = (int) Math.round(h / factor);
            byte[] layer = layerW == w && layerH == h
                    ? gray
                    : downscale(gray, w, h, layerW, layerH);
            IntegralImage ig = IntegralImage.compute(layer, layerW, layerH,
                    !cascade.lbp, false);
            cascade.prepareOffsets(ig.stride);
            int step = 2;
            for (int wy = 0; wy + cascade.winH <= layerH; wy += step) {
                for (int wx = 0; wx + cascade.winW <= layerW; wx += step) {
                    if (cascade.run(ig, wx, wy) > 0) {
                        candidates.add(new IntRect(
                                (int) Math.round(wx * factor),
                                (int) Math.round(wy * factor),
                                winW, winH));
                    }
                }
            }
            factor *= SCALE_FACTOR;
        }
        group(candidates, maxFaces, minNeighbors, out);
        return out.size();
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

    /** Bilinear downscale into a reused buffer (INTER_LINEAR like OpenCV). */
    private byte[] downscale(byte[] src, int sw, int sh, int dw, int dh) {
        int need = dw * dh;
        if (scaledBuf == null || scaledBuf.length < need) {
            scaledBuf = new byte[need];
        }
        byte[] out = scaledBuf;
        float sx = (float) sw / dw;
        float sy = (float) sh / dh;
        for (int y = 0; y < dh; y++) {
            float fy = y * sy;
            int fy0 = (int) fy;
            float ky = fy - fy0;
            int r0 = fy0 * sw;
            int r1 = Math.min(fy0 + 1, sh - 1) * sw;
            for (int x = 0; x < dw; x++) {
                float fx = x * sx;
                int fx0 = (int) fx;
                float kx = fx - fx0;
                int x1 = Math.min(fx0 + 1, sw - 1);
                int a = src[r0 + fx0] & 0xFF;
                int b = src[r0 + x1] & 0xFF;
                int c = src[r1 + fx0] & 0xFF;
                int d = src[r1 + x1] & 0xFF;
                float top = a + (b - a) * kx;
                float bot = c + (d - c) * kx;
                out[y * dw + x] = (byte) (top + (bot - top) * ky);
            }
        }
        return out;
    }
}