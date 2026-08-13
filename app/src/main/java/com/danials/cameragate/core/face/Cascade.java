package com.danials.cameragate.core.face;

/**
 * One OpenCV cascade classifier (BOOST stage type; HAAR or LBP features),
 * parsed from the classic cascade XML and evaluated with the exact same
 * arithmetic as OpenCV's CascadeClassifierImpl:
 *
 * <ul>
 *   <li>stages: each stage passes when its accumulated leaf sum is
 *       {@code >= stageThreshold - 1e-5}, holding {@code ntrees} weak trees
 *       starting at tree {@code first};</li>
 *   <li>trees: per-tree contiguous node lists (flat in {@code nodes});
 *       a node splits on its feature, then follows {@code left}/{@code
 *       right} — a leaf index (0 or negative) or the index of a child node
 *       within the same tree, exactly OpenCV's {@code nodes[root + idx]};
 *       the leaf picked is {@code leaves[leafOfs - idx]};</li>
 *   <li>LBP splits are categorical: the 8-bit local pattern indexes a
 *       {@code subsetSize}-word bitmask (256 bins);</li>
 *   <li>HAAR windows get a variance-normalization gate (OpenCV's
 *       HaarEvaluator::setWindow): normrect = window minus a 1px border,
 *       response = raw feature sum / sqrt(area*sqsum - sum^2), and flat
 *       windows are rejected outright; the square integral wraps 32-bit
 *       on purpose, exactly like OpenCV's CV_32S buffers.</li>
 * </ul>
 */
public final class Cascade {

    final boolean lbp;
    final boolean stumpBased;   // every tree is a single node (maxNodesPerTree == 1)
    final int winW;
    final int winH;
    final int ncategories;          // 0 = HAAR (ordered), 256 = LBP
    final int subsetSize;           // (ncategories + 31) / 32

    final int[] stageFirst;         // stage -> first tree index
    final int[] stageTrees;         // stage -> weak tree count
    final float[] stageThreshold;   // already epsilon-adjusted
    final int[] treeNodeCount;      // trees, in stage order
    final int[] nodes;              // flat 4-tuples: left, right, featureIdx, thresholdBits
    final float[] leaves;           // flat leaf pools, leafOfs advances by nodeCount+1 per tree
    final int[] subsets;            // LBP: nodeIdx*subsetSize words

    // features (window-relative): flat 5-tuples x,y,w,h,weight, up to 3 per feature
    final int[] featRects;
    final int[] featRectCount;
    final boolean[] featTilted;
    final int featureCount;

    // per-analysis-image offsets into the integral buffers
    int[] featOfs;                  // HAAR: 4 offsets x 3 rects per feature
    int[] lbpOfs;                   // LBP: 16 offsets per feature
    int ofsStride = -1;

    Cascade(boolean lbp, boolean stumpBased, int winW, int winH, int ncategories,
            int[] stageFirst, int[] stageTrees, float[] stageThreshold,
            int[] treeNodeCount, int[] nodes, float[] leaves, int[] subsets,
            int[] featRects, int[] featRectCount, boolean[] featTilted) {
        this.lbp = lbp;
        this.stumpBased = stumpBased;
        this.winW = winW;
        this.winH = winH;
        this.ncategories = ncategories;
        this.subsetSize = (ncategories + 31) / 32;
        this.stageFirst = stageFirst;
        this.stageTrees = stageTrees;
        this.stageThreshold = stageThreshold;
        this.treeNodeCount = treeNodeCount;
        this.nodes = nodes;
        this.leaves = leaves;
        this.subsets = subsets;
        this.featRects = featRects;
        this.featRectCount = featRectCount;
        this.featTilted = featTilted;
        this.featureCount = featRectCount.length;
    }

    /** Number of boosting stages. */
    public int stageCount() {
        return stageFirst.length;
    }

    /** Number of features (weak classifiers). */
    public int featureCount() {
        return featureCount;
    }

    /** Analysis window width in pixels. */
    public int windowWidth() {
        return winW;
    }

    /** Analysis window height in pixels. */
    public int windowHeight() {
        return winH;
    }

    /**
     * Precomputes integral-buffer offsets for every feature against the
     * given analysis stride so the hot loop is pure pointer arithmetic.
     */
    void prepareOffsets(int stride) {
        if (ofsStride == stride) {
            return;
        }
        this.ofsStride = stride;
        int n = featureCount;
        if (!lbp) {
            int[] ofs = new int[n * 12];
            for (int f = 0; f < n; f++) {
                int base = f * 5;
                int cnt = featRectCount[f];
                boolean tilt = featTilted[f];
                for (int r = 0; r < cnt; r++) {
                    int x = featRects[base + r * 5];
                    int y = featRects[base + r * 5 + 1];
                    int w = featRects[base + r * 5 + 2];
                    int h = featRects[base + r * 5 + 3];
                    int f0 = f * 12 + r * 4;
                    if (tilt) {
                        // CV_TILTED_PTRS
                        ofs[f0] = y * stride + x;
                        ofs[f0 + 1] = (y + h) * stride + x - h;
                        ofs[f0 + 2] = (y + w) * stride + x + w;
                        ofs[f0 + 3] = (y + w + h) * stride + x + w - h;
                    } else {
                        // CV_SUM_OFS
                        ofs[f0] = y * stride + x;
                        ofs[f0 + 1] = y * stride + x + w;
                        ofs[f0 + 2] = (y + h) * stride + x;
                        ofs[f0 + 3] = (y + h) * stride + x + w;
                    }
                }
            }
            this.featOfs = ofs;
            this.lbpOfs = null;
        } else {
            int[] ofs = new int[n * 16];
            for (int f = 0; f < n; f++) {
                // LBPEvaluator::OptFeature::setOffsets - 3x3 block grid
                int x = featRects[f * 5];
                int y = featRects[f * 5 + 1];
                int w = featRects[f * 5 + 2];
                int h = featRects[f * 5 + 3];
                int f0 = f * 16;
                // block (0,0)
                ofs[f0] = y * stride + x;
                ofs[f0 + 1] = y * stride + x + w;
                ofs[f0 + 4] = (y + h) * stride + x;
                ofs[f0 + 5] = (y + h) * stride + x + w;
                // block (2,0)
                ofs[f0 + 2] = y * stride + x + 2 * w;
                ofs[f0 + 3] = y * stride + x + 3 * w;
                ofs[f0 + 6] = (y + h) * stride + x + 2 * w;
                ofs[f0 + 7] = (y + h) * stride + x + 3 * w;
                // block (2,2)
                ofs[f0 + 10] = (y + 2 * h) * stride + x + 2 * w;
                ofs[f0 + 11] = (y + 2 * h) * stride + x + 3 * w;
                ofs[f0 + 14] = (y + 3 * h) * stride + x + 2 * w;
                ofs[f0 + 15] = (y + 3 * h) * stride + x + 3 * w;
                // block (0,2)
                ofs[f0 + 8] = (y + 2 * h) * stride + x;
                ofs[f0 + 9] = (y + 2 * h) * stride + x + w;
                ofs[f0 + 12] = (y + 3 * h) * stride + x;
                ofs[f0 + 13] = (y + 3 * h) * stride + x + w;
            }
            this.lbpOfs = ofs;
            this.featOfs = null;
        }
    }

    /**
     * Runs the cascade at window origin (wx, wy): 1 on acceptance, or a
     * negative code on rejection (stage index or variance gate).
     */
    int run(IntegralImage ig, int wx, int wy) {
        if (lbp) {
            return stumpBased ? runLbpStumps(ig, wx, wy) : runLbp(ig, wx, wy);
        }
        // HaarEvaluator::setWindow variance gate
        int stride = ig.stride;
        int[] sum = ig.sum;
        int[] sq = ig.sqsum;
        int base = wy * stride + wx;
        int off0 = stride + 1;                  // (1,1)
        int off1 = stride + 1 + (winW - 2);     // (winW-1, 1)
        int off2 = 1 + (winH - 2) * stride;     // (1, winH-1)
        int off3 = off1 + (winH - 2) * stride;  // (winW-1, winH-1)
        long valsum = sum[base + off0] - sum[base + off1]
                - sum[base + off2] + sum[base + off3];
        long valsq = (sq[base + off0] & 0xFFFFFFFFL)
                - (sq[base + off1] & 0xFFFFFFFFL)
                - (sq[base + off2] & 0xFFFFFFFFL)
                + (sq[base + off3] & 0xFFFFFFFFL);
        long area = (long) (winW - 2) * (winH - 2);
        long nf = area * valsq - valsum * valsum;
        if (nf <= 0) {
            return -1000;
        }
        double inv = 1.0 / Math.sqrt(nf);
        if (area * inv >= 1e-1) {
            return -1000; // window too flat
        }
        return stumpBased ? runHaarStagesStump(ig, base, inv) : runHaarStages(ig, base, inv);
    }

    /**
     * Stump path (OpenCV's predictOrderedStump): every tree is one node
     * with two leaves; the XML left/right node fields are ignored and the
     * tree's two consecutive leaf values are added directly.
     */
    private int runHaarStagesStump(IntegralImage ig, int base, double invNorm) {
        int[] sum = ig.sum;
        int[] ofs = featOfs;
        float[] leaves = this.leaves;
        int[] nodes = this.nodes;
        float[] thr = stageThreshold;
        int nStages = stageFirst.length;
        int tree = 0;
        for (int si = 0; si < nStages; si++) {
            double stageSum = 0;
            int ntrees = stageTrees[si];
            for (int wi = 0; wi < ntrees; wi++, tree++) {
                int nBase = tree;
                int fIdx = nodes[nBase * 4 + 2];
                int f0 = fIdx * 12;
                float fv = featRects[fIdx * 5 + 4] * (sum[base + ofs[f0]]
                        - sum[base + ofs[f0 + 1]]
                        - sum[base + ofs[f0 + 2]]
                        + sum[base + ofs[f0 + 3]]);
                int cnt = featRectCount[fIdx];
                if (cnt > 1) {
                    int f1 = f0 + 4;
                    fv += featRects[fIdx * 5 + 9] * (sum[base + ofs[f1]]
                            - sum[base + ofs[f1 + 1]]
                            - sum[base + ofs[f1 + 2]]
                            + sum[base + ofs[f1 + 3]]);
                    if (cnt > 2) {
                        int f2 = f0 + 8;
                        fv += featRects[fIdx * 5 + 14] * (sum[base + ofs[f2]]
                                - sum[base + ofs[f2 + 1]]
                                - sum[base + ofs[f2 + 2]]
                                + sum[base + ofs[f2 + 3]]);
                    }
                }
                float t = Float.intBitsToFloat(nodes[nBase * 4 + 3]);
                int li = tree * 2;
                stageSum += fv * invNorm < t ? leaves[li] : leaves[li + 1];
            }
            if (stageSum < thr[si]) {
                return -si - 1;
            }
        }
        return 1;
    }

    private int runHaarStages(IntegralImage ig, int base, double invNorm) {
        int[] sum = ig.sum;
        int[] ofs = featOfs;
        int[] featRC = featRectCount;
        int[] rects = featRects;
        float[] leaves = this.leaves;
        int[] nodes = this.nodes;
        float[] thr = stageThreshold;
        int[] sFirst = stageFirst;
        int[] sTrees = stageTrees;
        int nStages = stageFirst.length;
        int nodeOfs = 0;
        int leafOfs = 0;
        for (int si = 0; si < nStages; si++) {
            double stageSum = 0;
            int ntrees = sTrees[si];
            int t0 = sFirst[si];
            for (int wi = 0; wi < ntrees; wi++) {
                int nCount = treeNodeCount[t0 + wi];
                int idx = 0;
                int nBase;
                do {
                    nBase = nodeOfs + idx;
                    int fIdx = nodes[nBase * 4 + 2];
                    int f0 = fIdx * 12;
                    float fv = featRects[fIdx * 5 + 4] * (sum[base + ofs[f0]]
                            - sum[base + ofs[f0 + 1]]
                            - sum[base + ofs[f0 + 2]]
                            + sum[base + ofs[f0 + 3]]);
                    int cnt = featRC[fIdx];
                    if (cnt > 1) {
                        int f1 = f0 + 4;
                        fv += featRects[fIdx * 5 + 9] * (sum[base + ofs[f1]]
                                - sum[base + ofs[f1 + 1]]
                                - sum[base + ofs[f1 + 2]]
                                + sum[base + ofs[f1 + 3]]);
                        if (cnt > 2) {
                            int f2 = f0 + 8;
                            fv += featRects[fIdx * 5 + 14] * (sum[base + ofs[f2]]
                                    - sum[base + ofs[f2 + 1]]
                                    - sum[base + ofs[f2 + 2]]
                                    + sum[base + ofs[f2 + 3]]);
                        }
                    }
                    double val = fv * invNorm;
                    float t = Float.intBitsToFloat(nodes[nBase * 4 + 3]);
                    idx = val < t ? nodes[nBase * 4] : nodes[nBase * 4 + 1];
                } while (idx > 0);
                stageSum += leaves[leafOfs - idx];
                nodeOfs += nCount;
                leafOfs += nCount + 1;
            }
            if (stageSum < thr[si]) {
                return -si - 1;
            }
        }
        return 1;
    }

    /**
     * Stump path for LBP (OpenCV's predictCategoricalStump): like the Haar
     * stump path, but the split is a categorical subset lookup and the two
     * tree leaves are added directly.
     */
    private int runLbpStumps(IntegralImage ig, int wx, int wy) {
        int stride = ig.stride;
        int[] sum = ig.sum;
        int base = wy * stride + wx;
        int[] ofs = lbpOfs;
        float[] leaves = this.leaves;
        int[] nodes = this.nodes;
        int[] subsets = this.subsets;
        float[] thr = stageThreshold;
        int ss = subsetSize;
        int nStages = stageFirst.length;
        int tree = 0;
        for (int si = 0; si < nStages; si++) {
            double stageSum = 0;
            int ntrees = stageTrees[si];
            for (int wi = 0; wi < ntrees; wi++, tree++) {
                int nBase = tree;
                int fIdx = nodes[nBase * 4 + 2];
                int f0 = fIdx * 16;
                int cval = sum[base + ofs[f0 + 5]] - sum[base + ofs[f0 + 6]]
                        - sum[base + ofs[f0 + 9]] + sum[base + ofs[f0 + 10]];
                int pat = 0;
                if (sum[base + ofs[f0]] - sum[base + ofs[f0 + 1]]
                        - sum[base + ofs[f0 + 4]] + sum[base + ofs[f0 + 5]] >= cval) {
                    pat |= 128;
                }
                if (sum[base + ofs[f0 + 1]] - sum[base + ofs[f0 + 2]]
                        - sum[base + ofs[f0 + 5]] + sum[base + ofs[f0 + 6]] >= cval) {
                    pat |= 64;
                }
                if (sum[base + ofs[f0 + 2]] - sum[base + ofs[f0 + 3]]
                        - sum[base + ofs[f0 + 6]] + sum[base + ofs[f0 + 7]] >= cval) {
                    pat |= 32;
                }
                if (sum[base + ofs[f0 + 6]] - sum[base + ofs[f0 + 7]]
                        - sum[base + ofs[f0 + 10]] + sum[base + ofs[f0 + 11]] >= cval) {
                    pat |= 16;
                }
                if (sum[base + ofs[f0 + 10]] - sum[base + ofs[f0 + 11]]
                        - sum[base + ofs[f0 + 14]] + sum[base + ofs[f0 + 15]] >= cval) {
                    pat |= 8;
                }
                if (sum[base + ofs[f0 + 9]] - sum[base + ofs[f0 + 10]]
                        - sum[base + ofs[f0 + 13]] + sum[base + ofs[f0 + 14]] >= cval) {
                    pat |= 4;
                }
                if (sum[base + ofs[f0 + 8]] - sum[base + ofs[f0 + 9]]
                        - sum[base + ofs[f0 + 12]] + sum[base + ofs[f0 + 13]] >= cval) {
                    pat |= 2;
                }
                if (sum[base + ofs[f0 + 4]] - sum[base + ofs[f0 + 5]]
                        - sum[base + ofs[f0 + 8]] + sum[base + ofs[f0 + 9]] >= cval) {
                    pat |= 1;
                }
                int word = subsets[(tree - stageFirst[si]) * ss + (pat >> 5)];
                int li = tree * 2;
                stageSum += (word & (1 << (pat & 31))) != 0
                        ? leaves[li] : leaves[li + 1];
            }
            if (stageSum < thr[si]) {
                return -si - 1;
            }
        }
        return 1;
    }

    private int runLbp(IntegralImage ig, int wx, int wy) {
        int stride = ig.stride;
        int[] sum = ig.sum;
        int base = wy * stride + wx;
        int[] ofs = lbpOfs;
        float[] leaves = this.leaves;
        int[] nodes = this.nodes;
        int[] subsets = this.subsets;
        float[] thr = stageThreshold;
        int[] sFirst = stageFirst;
        int[] sTrees = stageTrees;
        int ss = subsetSize;
        int nStages = stageFirst.length;
        int nodeOfs = 0;
        int leafOfs = 0;
        for (int si = 0; si < nStages; si++) {
            double stageSum = 0;
            int ntrees = sTrees[si];
            int t0 = sFirst[si];
            for (int wi = 0; wi < ntrees; wi++) {
                int nCount = treeNodeCount[t0 + wi];
                int idx = 0;
                int nBase;
                do {
                    nBase = nodeOfs + idx;
                    int fIdx = nodes[nBase * 4 + 2];
                    int f0 = fIdx * 16;
                    int cval = sum[base + ofs[f0 + 5]] - sum[base + ofs[f0 + 6]]
                            - sum[base + ofs[f0 + 9]] + sum[base + ofs[f0 + 10]];
                    int pat = 0;
                    if (sum[base + ofs[f0]] - sum[base + ofs[f0 + 1]]
                            - sum[base + ofs[f0 + 4]] + sum[base + ofs[f0 + 5]] >= cval) {
                        pat |= 128;
                    }
                    if (sum[base + ofs[f0 + 1]] - sum[base + ofs[f0 + 2]]
                            - sum[base + ofs[f0 + 5]] + sum[base + ofs[f0 + 6]] >= cval) {
                        pat |= 64;
                    }
                    if (sum[base + ofs[f0 + 2]] - sum[base + ofs[f0 + 3]]
                            - sum[base + ofs[f0 + 6]] + sum[base + ofs[f0 + 7]] >= cval) {
                        pat |= 32;
                    }
                    if (sum[base + ofs[f0 + 6]] - sum[base + ofs[f0 + 7]]
                            - sum[base + ofs[f0 + 10]] + sum[base + ofs[f0 + 11]] >= cval) {
                        pat |= 16;
                    }
                    if (sum[base + ofs[f0 + 10]] - sum[base + ofs[f0 + 11]]
                            - sum[base + ofs[f0 + 14]] + sum[base + ofs[f0 + 15]] >= cval) {
                        pat |= 8;
                    }
                    if (sum[base + ofs[f0 + 9]] - sum[base + ofs[f0 + 10]]
                            - sum[base + ofs[f0 + 13]] + sum[base + ofs[f0 + 14]] >= cval) {
                        pat |= 4;
                    }
                    if (sum[base + ofs[f0 + 8]] - sum[base + ofs[f0 + 9]]
                            - sum[base + ofs[f0 + 12]] + sum[base + ofs[f0 + 13]] >= cval) {
                        pat |= 2;
                    }
                    if (sum[base + ofs[f0 + 4]] - sum[base + ofs[f0 + 5]]
                            - sum[base + ofs[f0 + 8]] + sum[base + ofs[f0 + 9]] >= cval) {
                        pat |= 1;
                    }
                    int word = subsets[nBase * ss + (pat >> 5)];
                    idx = (word & (1 << (pat & 31))) != 0
                            ? nodes[nBase * 4] : nodes[nBase * 4 + 1];
                } while (idx > 0);
                stageSum += leaves[leafOfs - idx];
                nodeOfs += nCount;
                leafOfs += nCount + 1;
            }
            if (stageSum < thr[si]) {
                return -si - 1;
            }
        }
        return 1;
    }
}