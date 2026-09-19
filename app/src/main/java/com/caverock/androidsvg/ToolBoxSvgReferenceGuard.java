package com.caverock.androidsvg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Narrow adapter to the pinned AndroidSVG 1.4 model. Keeping this in the renderer's package lets
 * validation use its parsed references and exact CSS matching, without reflection or a second CSS
 * implementation. A renderer upgrade must review this adapter along with its instrumentation tests.
 */
public final class ToolBoxSvgReferenceGuard {
    private ToolBoxSvgReferenceGuard() { }

    public static void requireAcyclic(SVG document) {
        Map<SVG.SvgObject, List<SVG.SvgObject>> edges = new IdentityHashMap<>();
        Map<SVG.SvgObject, String[]> styles = new IdentityHashMap<>();
        ArrayDeque<SVG.SvgObject> pending = new ArrayDeque<>();
        pending.add(document.getRootElement());
        while (!pending.isEmpty()) {
            SVG.SvgObject node = pending.removeFirst();
            List<SVG.SvgObject> next = new ArrayList<>();
            edges.put(node, next);
            String[] inherited = styles.get(node.parent);
            String[] refs = inherited == null ? new String[7] : Arrays.copyOf(inherited, 7);
            refs[5] = refs[6] = null; // clip-path and mask do not inherit.
            if (node instanceof SVG.SvgElementBase) {
                SVG.SvgElementBase element = (SVG.SvgElementBase) node;
                apply(refs, element.baseStyle);
                if (document.hasCSSRules()) {
                    for (CSSParser.Rule rule : document.getCSSRules()) {
                        if (CSSParser.ruleMatch(null, rule.selector, element)) apply(refs, rule.style);
                    }
                }
                apply(refs, element.style);
                if (node instanceof SVG.GraphicsElement || node instanceof SVG.TextContainer) {
                    for (int index = 0; index < 5; index++) add(document, next, refs[index]);
                }
                add(document, next, refs[5]);
                add(document, next, refs[6]);
            }
            styles.put(node, refs);
            if (node instanceof SVG.Use) add(document, next, ((SVG.Use) node).href);
            if (node instanceof SVG.TRef) add(document, next, ((SVG.TRef) node).href);
            if (node instanceof SVG.TextPath) add(document, next, ((SVG.TextPath) node).href);
            if (node instanceof SVG.GradientElement) add(document, next, ((SVG.GradientElement) node).href);
            if (node instanceof SVG.Pattern) add(document, next, ((SVG.Pattern) node).href);
            if (node instanceof SVG.SvgContainer) {
                for (SVG.SvgObject child : ((SVG.SvgContainer) node).getChildren()) {
                    next.add(child);
                    pending.addLast(child);
                }
            }
        }
        // Iterative DFS avoids consuming the Java stack for long, otherwise valid local chains.
        Map<SVG.SvgObject, Integer> state = new IdentityHashMap<>();
        for (SVG.SvgObject start : edges.keySet()) {
            if (state.containsKey(start)) continue;
            ArrayDeque<SVG.SvgObject> path = new ArrayDeque<>();
            ArrayDeque<Iterator<SVG.SvgObject>> iterators = new ArrayDeque<>();
            path.push(start);
            iterators.push(edges.get(start).iterator());
            state.put(start, 1);
            while (!path.isEmpty()) {
                if (!iterators.peek().hasNext()) {
                    state.put(path.pop(), 2);
                    iterators.pop();
                    continue;
                }
                SVG.SvgObject target = iterators.peek().next();
                Integer mark = state.get(target);
                if (mark != null && mark == 1) throw new IllegalArgumentException("Cyclic SVG reference");
                if (mark == null) {
                    state.put(target, 1);
                    path.push(target);
                    iterators.push(edges.get(target).iterator());
                }
            }
        }
    }

    private static void add(SVG document, List<SVG.SvgObject> edges, String reference) {
        if (reference == null) return;
        SVG.SvgObject target = document.resolveIRI(reference);
        if (target != null) edges.add(target);
    }

    private static String paint(SVG.SvgPaint value) {
        return value instanceof SVG.PaintReference ? ((SVG.PaintReference) value).href : null;
    }

    private static void apply(String[] refs, SVG.Style style) {
        if (style == null) return;
        long flags = style.specifiedFlags;
        if ((flags & SVG.SPECIFIED_FILL) != 0) refs[0] = paint(style.fill);
        if ((flags & SVG.SPECIFIED_STROKE) != 0) refs[1] = paint(style.stroke);
        if ((flags & SVG.SPECIFIED_MARKER_START) != 0) refs[2] = style.markerStart;
        if ((flags & SVG.SPECIFIED_MARKER_MID) != 0) refs[3] = style.markerMid;
        if ((flags & SVG.SPECIFIED_MARKER_END) != 0) refs[4] = style.markerEnd;
        if ((flags & SVG.SPECIFIED_CLIP_PATH) != 0) refs[5] = style.clipPath;
        if ((flags & SVG.SPECIFIED_MASK) != 0) refs[6] = style.mask;
    }
}
