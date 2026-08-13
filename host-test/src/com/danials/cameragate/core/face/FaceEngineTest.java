package com.danials.cameragate.core.face;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Host-side verification of the cascade engine (no Android anywhere:
 * the engine is plain Java). Checks, in order:
 *
 * <ol>
 *   <li>integral-image sums (incl. 32-bit wrapping) against brute force;</li>
 *   <li>evaluator mechanics on hand-built HAAR micro-cascades with
 *       deterministic accept/reject;</li>
 *   <li>LBP categorical split mechanics;</li>
 *   <li>the bundled haarcascade_frontalface_alt.xml: parser invariants
 *       (stage/tree/node/leaf/feature bookkeeping) plus structural sanity
 *       of every feature rect;</li>
 *   <li>end-to-end detector smoke tests: constant frames must yield zero
 *       faces (variance gate), noise/clock faces must run cleanly, with
 *       timing logged for the budget.</li>
 * </ol>
 */
public final class FaceEngineTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String what) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static byte[] gray(int w, int h, java.util.function.IntBinaryOperator f) {
        byte[] g = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                g[y * w + x] = (byte) f.applyAsInt(x, y);
            }
        }
        return g;
    }

    // ------------------------------------------------------------ integrals

    /**
     * Independent verbatim translation of OpenCV's integral_tilted_()
     * (cn = 1, no SIMD) with explicit 0-based indices, kept structurally
     * different from the engine's computeTilted so a transcription error
     * in one is caught by the other.
     */
    private static int[] opencvTiltedRef(byte[] gray, int w, int h) {
        int stride = w + 1;
        int[] t = new int[(h + 1) * stride];
        int[] buf = new int[w + 1];
        // row 1 of the buffer: raw source row 0 at cols 1..w
        for (int x = 0; x < w; x++) {
            int v = gray[x] & 0xFF;
            buf[x] = v;
            t[stride + 1 + x] = v;
        }
        if (w == 1) {
            buf[1] = 0;
        }
        for (int y = 1; y < h; y++) {
            int base = (y + 1) * stride + 1; // buffer row y+1, col 1
            int prev = y * stride + 1;       // buffer row y, col 1
            int t0 = gray[y * w] & 0xFF;
            t[base - 1] = t[prev - 1];       // col 0 border: copy from above (zero)
            t[base] = t[prev] + t0 + buf[1];
            for (int x = 1; x < w - 1; x++) { // cols 2..w-1
                int t1 = buf[x];
                buf[x - 1] = t1 + t0;
                t0 = gray[y * w + x] & 0xFF;
                t[base + x] = t1 + buf[x + 1] + t0 + t[prev + x - 1];
            }
            if (w > 1) {                     // col w
                int x = w - 1;
                int t1 = buf[x];
                buf[x - 1] = t1 + t0;
                t0 = gray[y * w + x] & 0xFF;
                t[base + x] = t0 + t1 + t[prev + x - 1];
                buf[x] = t0;
            }
        }
        return t;
    }

    private static void testIntegrals() {
        System.out.println("== integrals ==");
        Random rnd = new Random(42);
        int w = 33, h = 27;
        byte[] g = new byte[w * h];
        for (int i = 0; i < g.length; i++) {
            g[i] = (byte) rnd.nextInt(256);
        }
        IntegralImage ii = IntegralImage.compute(g, w, h, true, true);
        // axis-aligned rects incl. edge-touching
        int[][] rects = {{0, 0, w, h}, {0, 0, 1, 1}, {w - 1, h - 1, 1, 1},
                {5, 7, 12, 9}, {3, 2, 1, 1}, {0, h - 1, w, 1}, {w - 1, 0, 1, h}};
        for (int[] r : rects) {
            long want = 0;
            for (int y = r[1]; y < r[1] + r[3]; y++) {
                for (int x = r[0]; x < r[0] + r[2]; x++) {
                    want += g[y * w + x] & 0xFF;
                }
            }
            check(ii.rectSum(r[0], r[1], r[2], r[3]) == want,
                    "rectSum " + r[0] + "," + r[1] + " " + r[2] + "x" + r[3]);
            long sqWant = 0;
            for (int y = r[1]; y < r[1] + r[3]; y++) {
                for (int x = r[0]; x < r[0] + r[2]; x++) {
                    int v = g[y * w + x] & 0xFF;
                    sqWant += (long) v * v;
                }
            }
            long sqGot = (ii.sqsum[(r[1] + r[3]) * ii.stride + r[0] + r[2]]
                    & 0xFFFFFFFFL) - (ii.sqsum[r[1] * ii.stride + r[0] + r[2]] & 0xFFFFFFFFL)
                    - (ii.sqsum[(r[1] + r[3]) * ii.stride + r[0]] & 0xFFFFFFFFL)
                    + (ii.sqsum[r[1] * ii.stride + r[0]] & 0xFFFFFFFFL);
            check(sqGot == sqWant, "sqsum wrap-read " + r[1] + "," + r[0]);
        }
        // tilted integral: OpenCV's tilted features are NOT clean
        // parallelograms - the integral is built by a dedicated recursion
        // (integral_tilted_) and read out through the CV_TILTED_PTRS
        // corners - so the oracle is an independent verbatim translation
        // of that recursion compared against the whole table, plus golden
        // readouts on a tiny labeled image.
        int[] refT = opencvTiltedRef(g, w, h);
        boolean tblOk = true;
        for (int i = 0; i < refT.length; i++) {
            if (ii.tilted[i] != refT[i]) {
                tblOk = false;
            }
        }
        check(tblOk, "tilted table matches integral_tilted_ reference");
        byte[] tiny = new byte[16];
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                tiny[j * 4 + i] = (byte) (10 * i + j + 1);
            }
        }
        IntegralImage tii = IntegralImage.compute(tiny, 4, 4, false, true);
        check(tii.tiltedRectSum(1, 1, 1, 1) == 6, "tilted 1x1 golden at (1,1)");
        check(tii.tiltedRectSum(1, 0, 1, 1) == 3, "tilted 1x1 golden at (1,0)");
        check(tii.tiltedRectSum(2, 1, 1, 1) == 25, "tilted 1x1 golden at (2,1)");
        check(tii.tiltedRectSum(1, 2, 1, 1) == 21, "tilted 1x1 golden at (1,2)");
    }

    // ------------------------------------------------- micro-cascades

    /** Builds a 2-stage, 1-stump-per-stage HAAR cascade over the ramp image. */
    private static Cascade rampCascade(float s1, float l1a, float l1b,
                                       float s2, float l2a, float l2b) {
        // feature 0: left half; feature 1: right half of the 20x20 window
        int[] rects = {0, 0, 10, 20, 1, 10, 0, 10, 20, 1};
        int[] rectCount = {1, 1};
        boolean[] tilted = {false, false};
        // two stump trees, one node each; threshold bits unused for HAAR
        int[] nodes = { -1, 1, 0, Float.floatToIntBits(0f),
                -1, 1, 1, Float.floatToIntBits(0f)};
        int[] tc = {1, 1};
        float[] leaves = {l1a, l1b, l2a, l2b};
        int[] subs = {};
        float[] thr = {s1, s2};
        int[] first = {0, 1};
        int[] trees = {1, 1};
        return new Cascade(false, true, 20, 20, 0, first, trees, thr,
                tc, nodes, leaves, subs, rects, rectCount, tilted);
    }

    private static void testHaarMicro() {
        System.out.println("== HAAR micro-cascade ==");
        // Steep x-ramp so the variance gate (normrect 18x18) passes; every
        // analysis window then has the same normalized response scale, and
        // feature sums are exact integers. The thresholds below are applied
        // AFTER the OpenCV variance normalization (fv * 1/sqrt(nf)):
        //   window sums: left-half / right-half
        //     (0,0)  : 6300 / 20300
        //     (10,0) : 20300 / 34300
        //     (0,20) : 6300 / 20300
        //   normalized (fv*inv): stage 1 in {0.535, 1.725}, stage 2 in
        //   {1.725, 2.915} - thresholds 0.5 / 1.0 sit between the values,
        //   so every window deterministically takes the right-branch leaf.
        byte[] g = gray(40, 40, (x, y) -> 7 * x);
        IntegralImage ig = IntegralImage.compute(g, 40, 40, true, false);
        // accept path: stage sums are always the right-branch leaves
        Cascade accept = rampCascade(0.5f, 0.5f, 1.5f, 1.0f, 1f, 2f);
        accept.prepareOffsets(ig.stride);
        check(accept.run(ig, 0, 0) == 1, "ramp window accepted at (0,0)");
        check(accept.run(ig, 10, 0) == 1, "ramp window accepted at (10,0)");
        check(accept.run(ig, 0, 20) == 1, "ramp window accepted at (0,20)");
        // reject path: stage 2 always takes its right leaf (-3), which is
        // below the stage-2 threshold
        Cascade reject = rampCascade(0.5f, 0.5f, 1.5f, -1.5f, -2f, -3f);
        reject.prepareOffsets(ig.stride);
        check(reject.run(ig, 0, 0) == -2, "rejected at stage 2 (code -2)");
        // variance gate: constant window is flat
        byte[] flat = gray(40, 40, (x, y) -> 99);
        IntegralImage fg = IntegralImage.compute(flat, 40, 40, true, false);
        check(accept.run(fg, 0, 0) == -1000, "flat window gated (variance)");
    }

    private static void testLbpMicro() {
        System.out.println("== LBP micro-cascade ==");
        // one stage, one stump over feature 0 (rect 0,0,4,4 -> 12x12 grid
        // of 4x4 blocks); all-dark window: every block sum == cval so all
        // 8 bits set -> pattern 255 -> subset bit 255 -> left leaf
        int[] rects = {0, 0, 4, 4, 1};
        int[] rectCount = {1};
        boolean[] tilted = {false};
        int ncat = 256;
        int[] nodes = {-1, 1, 0, 0};
        int[] tc = {1};
        float[] leaves = {1.0f, -1.0f};
        int[] subs = new int[8];
        subs[255 >> 5] |= 1 << (255 & 31);
        float[] thr = {0.5f};
        int[] first = {0};
        int[] trees = {1};
        Cascade c = new Cascade(true, true, 20, 20, ncat, first, trees, thr,
                tc, nodes, leaves, subs, rects, rectCount, tilted);
        byte[] dark = gray(40, 40, (x, y) -> 5);
        IntegralImage ig = IntegralImage.compute(dark, 40, 40, false, false);
        c.prepareOffsets(ig.stride);
        check(c.run(ig, 0, 0) == 1, "all-dark LBP window accepted (pat 255)");
        // checkerboard left block bright: block(0,0) > cval -> bit 7 off
        byte[] check2 = gray(40, 40, (x, y) -> ((x / 4) + (y / 4)) % 2 == 0
                ? 200 : 10);
        int subs2[] = new int[8];
        subs2[0] = 1 << 0; // pattern 0 -> left leaf
        Cascade c2 = new Cascade(true, true, 20, 20, ncat, first, trees, thr,
                tc, nodes, leaves, subs2, rects, rectCount, tilted);
        IntegralImage ig2 = IntegralImage.compute(check2, 40, 40, false, false);
        c2.prepareOffsets(ig2.stride);
        check(c2.run(ig2, 0, 0) == -1, "checkerboard LBP rejected (pattern 0)");
        // two stages over the same feature: OpenCV's predictCategoricalStump
        // indexes subsets per-stage (by wi), not by the global tree index, so
        // stage 2 must consult its own 8 words even though this is tree 1
        int[] rects2 = {0, 0, 4, 4, 1, 0, 0, 4, 4, 1};
        int[] rc2 = {1, 1};
        boolean[] tilt2 = {false, false};
        int[] nodes2 = {-1, 1, 0, 0, -1, 1, 1, 0};
        int[] tc2 = {1, 1};
        float[] leaves2 = {1.0f, -1.0f, 1.0f, -1.0f};
        int[] subs3 = new int[16];
        subs3[7] = 1 << 31; // pattern 255 selectable only in stage 1's subset
        float[] thr2 = {0.5f, 0.5f};
        int[] first2 = {0, 1};
        int[] trees2 = {1, 1};
        Cascade c3 = new Cascade(true, true, 20, 20, ncat, first2, trees2, thr2,
                tc2, nodes2, leaves2, subs3, rects2, rc2, tilt2);
        IntegralImage ig3 = IntegralImage.compute(dark, 40, 40, false, false);
        c3.prepareOffsets(ig3.stride);
        check(c3.run(ig3, 0, 0) == 1, "2-stage LBP: subsets are per-stage");
    }

    // --------------------------------------------------- real cascade

    private static void testRealCascade() throws Exception {
        System.out.println("== haarcascade_frontalface_alt.xml ==");
        long t0 = System.nanoTime();
        InputStream in = FaceEngineTest.class.getResourceAsStream(
                "/cascades/haarcascade_frontalface_alt.xml");
        check(in != null, "cascade resource present");
        if (in == null) {
            return;
        }
        Cascade c;
        try {
            c = CascadeParser.parse(in);
        } finally {
            in.close();
        }
        long parseMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  parse " + parseMs + "ms");
        check(!c.lbp, "featureType is HAAR");
        check(c.winW == 20 && c.winH == 20, "window 20x20");
        check(c.stumpBased, "stump-based cascade (maxNodesPerTree == 1)");
        check(c.stageCount() > 0, "stages parsed: " + c.stageCount());
        check(c.featureCount() > 1000, "features parsed: " + c.featureCount());
        check(c.stageFirst.length == c.stageThreshold.length,
                "one threshold per stage");
        int nodeCount = 0, leafCount = 0, treeCount = c.treeNodeCount.length;
        for (int t = 0; t < treeCount; t++) {
            int n = c.treeNodeCount[t];
            check(n >= 1, "tree " + t + " has nodes");
            nodeCount += n;
            leafCount += n + 1;
        }
        check(nodeCount == c.nodes.length / 4,
                "nodes bookkeeping (" + nodeCount + " nodes)");
        check(leafCount == c.leaves.length,
                "leaves bookkeeping (" + leafCount + " leaves)");
        int treeSum = 0;
        for (int s = 0; s < c.stageCount(); s++) {
            treeSum += c.stageTrees[s];
            check(c.stageFirst[s] + c.stageTrees[s] <= treeCount,
                    "stage " + s + " tree range in bounds");
            check(c.stageFirst[s] >= 0, "stage " + s + " first tree >= 0");
        }
        check(treeSum == treeCount, "stage tree ranges cover all trees");
        check(c.featureCount == c.featRectCount.length,
                "one rect-count per feature");
        check(c.featureCount == c.featTilted.length,
                "one tilted flag per feature");
        int rcTotal = 0;
        boolean allRectsOk = true;
        for (int f = 0; f < c.featureCount; f++) {
            int rc = c.featRectCount[f];
            if (rc < 1 || rc > 3) {
                allRectsOk = false;
            }
            rcTotal += rc;
            for (int r = 0; r < rc; r++) {
                int base = f * 5 + r * 5;
                int x = c.featRects[base], y = c.featRects[base + 1];
                int w = c.featRects[base + 2], h = c.featRects[base + 3];
                if (w < 1 || h < 1 || x < 0 || y < 0
                        || x + w > 20 || y + h > 20) {
                    allRectsOk = false;
                }
            }
            if (c.featTilted[f]) {
                System.out.println("  note: feature " + f + " is tilted");
            }
        }
        check(allRectsOk, "all " + rcTotal + " feature rects inside the window");
        // every feature index referenced by a node must be in the table
        boolean idxInRange = true;
        for (int ni = 0; ni < c.nodes.length / 4; ni++) {
            if (c.nodes[ni * 4 + 2] < 0 || c.nodes[ni * 4 + 2] >= c.featureCount) {
                idxInRange = false;
            }
        }
        check(idxInRange, "every node feature index in range");
        // every subset word in range for its node
        boolean subsetsOk = true;
        int ss = c.subsetSize;
        if (c.subsetSize > 0) {
            for (int ni = 0; ni < c.nodes.length / 4; ni++) {
                int min = ni * ss;
                int max = min + ss;
                if (max > c.subsets.length) {
                    subsetsOk = false;
                }
            }
        }
        check(subsetsOk, "subset table sized for every LBP node");
    }

    // ------------------------------------------------------- detector

    private static void testDetector(Cascade c) throws Exception {
        System.out.println("== FaceDetector core ==");
        FaceDetector d = new FaceDetector(c);
        List<IntRect> out = new ArrayList<IntRect>(8);
        // constant image: variance gate kills every window
        byte[] flat = gray(240, 180, (x, y) -> 128);
        check(d.detect(flat, 240, 180, 4, out) == 0,
                "flat frame -> 0 faces");
        // random noise: engine runs every scale without exceptions
        Random rnd = new Random(7);
        byte[] noise = new byte[240 * 180];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) rnd.nextInt(256);
        }
        long t0 = System.nanoTime();
        int n = d.detect(noise, 240, 180, 4, out);
        long dt = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  noise 240x180 detect " + dt + "ms -> " + n);
        check(n >= 0 && n <= 4, "noise detect bounded (" + n + ")");
        for (IntRect r : out) {
            check(r.width > 0 && r.height > 0
                    && r.x >= 0 && r.y >= 0
                    && r.x + r.width <= 240 && r.y + r.height <= 180,
                    "box inside frame " + r);
        }
        // tiny frames: supports() guard
        check(d.detect(flat, 4, 4, 4, out) == 0, "tiny frame -> 0 faces");
        // a crude synthetic face with eyelike dark patches at 80x60
        byte[] face = syntheticFace(160, 120);
        t0 = System.nanoTime();
        n = d.detect(face, 160, 120, 4, out);
        dt = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  synthetic face 160x120 detect " + dt + "ms -> " + n);
        // a real cascade trained on real faces need not accept our fake;
        // the contract here is just clean execution and bounded output
        check(n >= 0 && n <= 4, "face frame detect bounded (" + n + ")");
        if (n > 0) {
            IntRect r = out.get(0);
            System.out.println("  " + r);
        }
    }

    /** Skin-tone blob with dark eye row and dark mouth bar, mild noise. */
    private static byte[] syntheticFace(int w, int h) {
        Random rnd = new Random(3);
        byte[] g = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 168; // skin
                if (y > h / 3 && y < h / 3 + 3 && (x < w / 3 || x > 2 * w / 3)) {
                    v = 40; // eyes
                } else if (y > 2 * h / 3 && y < 2 * h / 3 + 2
                        && x > w / 3 && x < 2 * w / 3) {
                    v = 70; // mouth
                } else if (x > w / 2) {
                    v = 160; // shading so the window is not too flat
                }
                g[y * w + x] = (byte) v;
            }
        }
        return g;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("CameraGate face engine host tests");
        testIntegrals();
        testHaarMicro();
        testLbpMicro();
        testRealCascade();
        Cascade real = null;
        InputStream in = FaceEngineTest.class.getResourceAsStream(
                "/cascades/haarcascade_frontalface_alt.xml");
        if (in != null) {
            try {
                real = CascadeParser.parse(in);
            } finally {
                in.close();
            }
        }
        if (real != null) {
            testDetector(real);
        }
        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}