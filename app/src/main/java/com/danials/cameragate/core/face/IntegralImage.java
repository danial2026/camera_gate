package com.danials.cameragate.core.face;

/**
 * Integral images over a grayscale buffer.
 *
 * <p>Port of the exact math used by OpenCV's cascade detector
 * (modules/imgproc/src/sumpixels.dispatch.cpp) so the face detector's
 * responses match OpenCV's bit-for-bit in the general case:
 * <ul>
 *   <li>{@code sum}: axis-aligned integral with a zero border row/column,
 *       ({@code w+1}) x ({@code h+1}) ints; sums never overflow for the
 *       analysis sizes used here (255 * ~1M pixels &lt; 2^31).</li>
 *   <li>{@code sqsum}: sum of squares, kept as wrapped 32-bit like OpenCV's
 *       CV_32S square integral (callers must read it back unsigned -
 *       exactly how OpenCV's HaarEvaluator does).</li>
 *   <li>{@code tilted}: the 45-degree rotated integral (only computed when
 *       requested; none of the bundled cascades need it).</li>
 * </ul>
 */
public final class IntegralImage {

    final int w;
    final int h;
    final int stride;             // w + 1
    int[] sum;                    // (w+1)*(h+1)
    int[] sqsum;                  // (w+1)*(h+1), wrapped 32-bit
    int[] tilted;                 // (w+1)*(h+1) when needTilted

    IntegralImage(int w, int h, boolean needSq, boolean needTilted) {
        this.w = w;
        this.h = h;
        this.stride = w + 1;
        int n = stride * (h + 1);
        this.sum = new int[n];
        if (needSq) {
            this.sqsum = new int[n];
        }
        if (needTilted) {
            this.tilted = new int[n];
        }
    }

    /**
     * Builds the integrals for one scale layer. Exact port of OpenCV's
     * integral_() (modules/imgproc/src/sumpixels.dispatch.cpp) loop order:
     * the buffer's row 0 and column 0 stay zero, row 1 holds the prefix
     * sums of source row 0, and every later row is the standard 2D
     * recursion - so index {@code (x, y)} of the buffer holds the sum of
     * the rectangle {@code [0,x) x [0,y)}, exactly like OpenCV's CV_32S
     * sums, and feature offsets written for OpenCV's layout work here
     * unchanged.
     */
    static IntegralImage compute(byte[] gray, int w, int h,
                                 boolean needSq, boolean needTilted) {
        IntegralImage ii = new IntegralImage(w, h, needSq, needTilted);
        int[] sum = ii.sum;
        int[] sqsum = ii.sqsum;
        int stride = ii.stride;

        // row 1 (buffer row 0 stays zero): prefix sums of source row 0
        for (int x = 0; x < w; x++) {
            int v = gray[x] & 0xFF;
            sum[stride + x + 1] = sum[stride + x] + v;
            if (needSq) {
                sqsum[stride + x + 1] = sqsum[stride + x] + v * v;
            }
        }

        for (int y = 1; y < h; y++) {
            int rowBase = (y + 1) * stride;   // buffer row y+1
            int prevBase = rowBase - stride;  // buffer row y
            int g = y * w;
            for (int x = 0; x < w; x++) {
                int v = gray[g + x] & 0xFF;
                sum[rowBase + x + 1] = sum[prevBase + x + 1]
                        + sum[rowBase + x] - sum[prevBase + x] + v;
                if (needSq) {
                    sqsum[rowBase + x + 1] = sqsum[prevBase + x + 1]
                            + sqsum[rowBase + x] - sqsum[prevBase + x]
                            + v * v;
                }
            }
        }

        if (needTilted) {
            computeTilted(ii, gray, w, h);
        }
        return ii;
    }

    /**
     * The tilted integral, ported line by line from OpenCV's
     * integral_tilted_(): buffer row 1 holds the raw source row 0, each
     * following row uses the diagonal recursion with a one-row-lookahead
     * buffer of the previous source row.
     */
    private static void computeTilted(IntegralImage ii, byte[] gray,
                                      int w, int h) {
        int[] tilted = ii.tilted;
        int stride = ii.stride;
        int[] buf = new int[w + 1];
        // row 1 of the buffer = raw source row 0 (buffer row 0 stays 0)
        for (int x = 0; x < w; x++) {
            int v = gray[x] & 0xFF;
            buf[x] = v;
            tilted[stride + x + 1] = v;
        }
        buf[w] = 0;
        for (int y = 1; y < h; y++) {
            int rowBase = (y + 1) * stride;
            int prevBase = rowBase - stride;
            int g = y * w;
            int t0 = gray[g] & 0xFF;
            tilted[rowBase + 1] = tilted[prevBase + 1] + t0 + buf[1];
            for (int x = 1; x < w - 1; x++) {
                int t1 = buf[x];
                buf[x - 1] = t1 + t0;
                t0 = gray[g + x] & 0xFF;
                tilted[rowBase + x + 1] = t1 + buf[x + 1] + t0
                        + tilted[prevBase + x];
            }
            if (w > 1) {
                int t1 = buf[w - 1];
                buf[w - 2] = t1 + t0;
                t0 = gray[g + w - 1] & 0xFF;
                tilted[rowBase + w] = t0 + t1 + tilted[prevBase + w - 1];
                buf[w - 1] = t0;
            }
        }
    }

    /** Sum of the axis-aligned rectangle, integral-relative coords. */
    int rectSum(int x, int y, int rw, int rh) {
        int a = sum[y * stride + x];
        int b = sum[y * stride + x + rw];
        int c = sum[(y + rh) * stride + x];
        int d = sum[(y + rh) * stride + x + rw];
        return d - b - c + a;
    }

    /** Sum of the 45-degree tilted rectangle, OpenCV CV_TILTED_PTRS order. */
    int tiltedRectSum(int x, int y, int rw, int rh) {
        int[] t = tilted;
        int p0 = t[y * stride + x];
        int p1 = t[(y + rh) * stride + x - rh];
        int p2 = t[(y + rw) * stride + x + rw];
        int p3 = t[(y + rw + rh) * stride + x + rw - rh];
        return p0 - p1 - p2 + p3;
    }
}