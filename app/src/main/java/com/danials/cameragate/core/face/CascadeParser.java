package com.danials.cameragate.core.face;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal parser for OpenCV's cascade XML format (the modern "new format"
 * used by OpenCV 2.4.11+ / 3.x / 4.x), hand-rolled so the engine has no
 * runtime dependencies and works on Android 4.x.
 *
 * <p>Known layout:
 * <pre>
 * &lt;cascade&gt;
 *   &lt;stageType&gt;BOOST&lt;/stageType&gt;
 *   &lt;featureType&gt;HAAR|LBP&lt;/featureType&gt;
 *   &lt;height&gt;20&lt;/height&gt; &lt;width&gt;20&lt;/width&gt;
 *   &lt;featureParams&gt;&lt;maxCatCount&gt;0|256&lt;/maxCatCount&gt;&lt;/featureParams&gt;
 *   &lt;stages&gt;
 *     &lt;_&gt;  &lt;!-- one per stage --&gt;
 *       &lt;stageThreshold&gt;1.2e-02&lt;/stageThreshold&gt;
 *       &lt;weakClassifiers&gt;
 *         &lt;_&gt;  &lt;!-- one per weak tree --&gt;
 *           &lt;internalNodes&gt;left right featureIdx threshold [subset...]&lt;/internalNodes&gt;
 *           &lt;leafValues&gt;l0 l1 ...&lt;/leafValues&gt;
 *         &lt;/_&gt;
 *       &lt;/weakClassifiers&gt;
 *     &lt;/_&gt;
 *   &lt;/stages&gt;
 *   &lt;features&gt;
 *     &lt;_&gt;
 *       &lt;rects&gt;&lt;_&gt;x y w h weight&lt;/_&gt;...&lt;/rects&gt;  &lt;!-- HAAR --&gt;
 *       [&lt;tilted&gt;1&lt;/tilted&gt;]
 *       &lt;rect&gt;x y w h&lt;/rect&gt;                           &lt;!-- LBP --&gt;
 *     &lt;/_&gt;
 *   &lt;/features&gt;
 * &lt;/cascade&gt;
 * </pre>
 */
public final class CascadeParser {

    private static final float THRESHOLD_EPS = 1e-5f;

    private String featureType;
    private int winW;
    private int winH;
    private int ncategories;
    private int stageTreeStart;          // treeNodeCount at stage open
    private int rectCount;               // rects seen for the open feature
    private int maxNodesPerTree;
    private final List<Integer> stageFirst = new ArrayList<Integer>();
    private final List<Integer> stageTrees = new ArrayList<Integer>();
    private final List<Float> stageThreshold = new ArrayList<Float>();
    private final List<Integer> treeNodeCount = new ArrayList<Integer>();
    private final List<Integer> nodes = new ArrayList<Integer>();
    private final List<Float> leaves = new ArrayList<Float>();
    private final List<Integer> subsets = new ArrayList<Integer>();
    private final List<int[]> featRects = new ArrayList<int[]>();
    private final List<Integer> featRectCount = new ArrayList<Integer>();
    private final List<Boolean> featTilted = new ArrayList<Boolean>();

    /** Parses one cascade file. */
    public static Cascade parse(InputStream in) throws IOException {
        return new CascadeParser().parseInternal(in);
    }

    private Cascade parseInternal(InputStream in) throws IOException {
        char[] buf = new char[8192];
        StringBuilder sb = new StringBuilder(1 << 20);
        Reader r = new InputStreamReader(in, "UTF-8");
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        scan(sb.toString());
        if (featureType == null || winW <= 0 || winH <= 0
                || stageThreshold.isEmpty() || featRectCount.isEmpty()
                || stageFirst.size() != stageThreshold.size()) {
            throw new IOException("cascade XML incomplete: stages="
                    + stageThreshold.size() + " features=" + featRectCount.size());
        }
        boolean lbp = "LBP".equals(featureType);
        int trees = treeNodeCount.size();
        int[] sFirst = new int[stageFirst.size()];
        int[] sTrees = new int[stageTrees.size()];
        float[] sThr = new float[stageThreshold.size()];
        for (int i = 0; i < sFirst.length; i++) {
            sFirst[i] = stageFirst.get(i);
            sTrees[i] = stageTrees.get(i);
            sThr[i] = stageThreshold.get(i) - THRESHOLD_EPS;
        }
        int[] tc = new int[trees];
        for (int i = 0; i < trees; i++) {
            tc[i] = treeNodeCount.get(i);
        }
        int[] nds = new int[nodes.size()];
        for (int i = 0; i < nds.length; i++) {
            nds[i] = nodes.get(i);
        }
        float[] lvs = new float[leaves.size()];
        for (int i = 0; i < lvs.length; i++) {
            lvs[i] = leaves.get(i);
        }
        int[] subs = new int[subsets.size()];
        for (int i = 0; i < subs.length; i++) {
            subs[i] = subsets.get(i);
        }
        int frSize = 0;
        for (int[] fr : featRects) {
            frSize += fr.length;
        }
        int[] fr = new int[frSize];
        int f = 0;
        for (int[] a : featRects) {
            for (int v : a) {
                fr[f++] = v;
            }
        }
        int[] frc = new int[featRectCount.size()];
        boolean[] ft = new boolean[featTilted.size()];
        for (int i = 0; i < frc.length; i++) {
            frc[i] = featRectCount.get(i);
            ft[i] = featTilted.get(i);
        }
        return new Cascade(lbp, maxNodesPerTree == 1, winW, winH, ncategories,
                sFirst, sTrees, sThr, tc, nds, lvs, subs, fr, frc, ft);
    }

    private void scan(String s) throws IOException {
        String[] stack = new String[20];
        int depth = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            int tagStart = s.indexOf('<', i);
            if (tagStart < 0) {
                break;
            }
            if (s.startsWith("<!--", tagStart)) {
                int end = s.indexOf("-->", tagStart);
                if (end < 0) {
                    throw new IOException("unterminated comment");
                }
                i = end + 3;
                continue;
            }
            int tagEnd = s.indexOf('>', tagStart);
            if (tagEnd < 0) {
                throw new IOException("unterminated tag");
            }
            String tag = s.substring(tagStart + 1, tagEnd).trim();
            i = tagEnd + 1;
            if (tag.length() == 0 || tag.startsWith("?")) {
                continue; // xml prolog / doctype artifacts
            }
            if (tag.startsWith("/")) {
                closeTag(tag.substring(1));
                depth--;
                continue;
            }
            int textEnd = s.indexOf('<', i);
            if (textEnd < 0) {
                textEnd = n;
            }
            String text = s.substring(i, textEnd).trim();
            i = textEnd;
            if (depth >= stack.length) {
                throw new IOException("markup too deep");
            }
            stack[depth++] = tag;
            openTag(stack, depth, tag, text);
        }
    }

    private void openTag(String[] stack, int depth, String tag, String text)
            throws IOException {
        String parent = depth >= 2 ? stack[depth - 2] : null;
        if ("_".equals(tag) && "features".equals(parent)) {
            // begin one feature: for HAAR the rects follow in <rects>
            if ("HAAR".equals(featureType)) {
                rectCount = 0;
                featTilted.add(Boolean.FALSE);
            }
            return;
        }
        if ("_".equals(tag) && "rects".equals(parent)) {
            featRects.add(parseRect(text, 5));
            rectCount++;
            return;
        }
        if ("rect".equals(tag)) {
            // LBP feature: exactly one rect per feature
            featRects.add(parseRect(text, 4));
            featRectCount.add(1);
            featTilted.add(Boolean.FALSE);
            return;
        }
        if ("weakClassifiers".equals(tag)) {
            stageTreeStart = treeNodeCount.size();
            return;
        }
        if ("stageType".equals(tag)) {
            if (!"BOOST".equals(text)) {
                throw new IOException("unsupported stageType: " + text);
            }
        } else if ("featureType".equals(tag)) {
            featureType = text;
        } else if ("height".equals(tag)) {
            winH = (int) Double.parseDouble(text);
        } else if ("width".equals(tag)) {
            winW = (int) Double.parseDouble(text);
        } else if ("maxCatCount".equals(tag)) {
            ncategories = (int) Double.parseDouble(text);
        } else if ("stageThreshold".equals(tag)) {
            stageThreshold.add(Float.parseFloat(text));
        } else if ("internalNodes".equals(tag)) {
            int nodeStep = 3 + (ncategories > 0 ? (ncategories + 31) / 32 : 1);
            String[] tok = split(text);
            if (tok.length % nodeStep != 0) {
                throw new IOException("internalNodes count " + tok.length
                        + " not a multiple of nodeStep " + nodeStep);
            }
            int count = tok.length / nodeStep;
            if (count > maxNodesPerTree) {
                maxNodesPerTree = count;
            }
            treeNodeCount.add(count);
            for (int k = 0; k < count; k++) {
                int base = k * nodeStep;
                nodes.add((int) Double.parseDouble(tok[base]));
                nodes.add((int) Double.parseDouble(tok[base + 1]));
                nodes.add((int) Double.parseDouble(tok[base + 2]));
                if (ncategories > 0) {
                    int ss = (ncategories + 31) / 32;
                    for (int j = 0; j < ss; j++) {
                        subsets.add((int) Double.parseDouble(tok[base + 3 + j]));
                    }
                } else {
                    nodes.add(Float.floatToIntBits(
                            Float.parseFloat(tok[base + 3])));
                }
            }
        } else if ("leafValues".equals(tag)) {
            for (String t : split(text)) {
                leaves.add(Float.parseFloat(t));
            }
        } else if ("tilted".equals(tag) && !featTilted.isEmpty()) {
            featTilted.set(featTilted.size() - 1, "1".equals(text));
        }
    }

    private void closeTag(String tag) throws IOException {
        if ("weakClassifiers".equals(tag)) {
            stageTrees.add(treeNodeCount.size() - stageTreeStart);
            stageFirst.add(stageTreeStart);
        } else if ("rects".equals(tag)) {
            featRectCount.add(rectCount);
        }
    }

    private int[] parseRect(String text, int fields) throws IOException {
        String[] tok = split(text);
        if (tok.length != fields) {
            throw new IOException("rect needs " + fields + " numbers: " + text);
        }
        int[] r = new int[5];
        for (int j = 0; j < fields; j++) {
            r[j] = (int) Double.parseDouble(tok[j]);
        }
        if (fields == 4) {
            r[4] = 1; // LBP block: weight unused
        } else {
            r[4] = (int) Math.round(Double.parseDouble(tok[4]));
        }
        return r;
    }

    private static String[] split(String s) {
        List<String> out = new ArrayList<String>(8);
        int start = -1;
        int n = s.length();
        for (int i = 0; i <= n; i++) {
            boolean wspace = i == n || s.charAt(i) == ' '
                    || s.charAt(i) == '\t' || s.charAt(i) == '\n'
                    || s.charAt(i) == '\r';
            if (wspace) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        return out.toArray(new String[out.size()]);
    }
}