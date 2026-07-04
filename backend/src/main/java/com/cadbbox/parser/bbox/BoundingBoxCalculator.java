package com.cadbbox.parser.bbox;

import com.cadbbox.parser.step.StepEntity;
import com.cadbbox.parser.step.StepParser.ParsedStepFile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Computes a tight axis-aligned bounding box for a single leaf part by
 * accumulating every {@code CARTESIAN_POINT} reachable from that part's shape
 * representation.
 *
 * <p>Slice 1 scope: the part's points are taken <em>as-is</em> in the file's
 * coordinate frame (no instance-transform chain yet — that arrives in Slice 2).
 * For the single-part golden sample this is correct because the sample carries
 * no assembly transform; the AABB is in the file's native frame, which for a
 * leaf extracted directly from GMC2550WRS is the assembly-root frame anyway.
 *
 * <p>Algorithm: find the part's {@code MANIFOLD_*_SHAPE_REPRESENTATION} (or any
 * representation entity), then transitively walk every referenced entity,
 * including the coordinates of every {@code CARTESIAN_POINT} encountered.
 */
@Component
public class BoundingBoxCalculator {

    /**
     * Compute the AABB of the (single) part described by this parsed file.
     * Throws if no points are found.
     */
    public BoundingBox computeForSinglePart(ParsedStepFile file) {
        Map<Integer, StepEntity> ents = file.entities();
        StepEntity shapeRep = findShapeRepresentation(ents);
        if (shapeRep == null) {
            throw new IllegalStateException("No shape representation found in STEP file");
        }
        return accumulatePoints(shapeRep.id(), ents);
    }

    private StepEntity findShapeRepresentation(Map<Integer, StepEntity> ents) {
        // Prefer the *_SHAPE_REPRESENTATION entities; otherwise fall back to any
        // ADVANCED_BREP / MANIFOLD_SOLID_BREP that directly carries geometry.
        StepEntity fallback = null;
        for (StepEntity e : ents.values()) {
            String t = e.type();
            if (t.endsWith("_SHAPE_REPRESENTATION") || t.endsWith("_BREP")
                    || t.startsWith("MANIFOLD_") || t.equals("ADVANCED_BREP_SHAPE_REPRESENTATION")) {
                if (t.endsWith("_REPRESENTATION")) return e;
                if (fallback == null) fallback = e;
            }
        }
        // Last resort: if there's exactly one PRODUCT_DEFINITION, use whatever
        // SHAPE_DEFINITION_REPRESENTATION points at.
        if (fallback == null) {
            for (StepEntity e : ents.values()) {
                if (e.type().equals("SHAPE_DEFINITION_REPRESENTATION") && e.args().size() >= 2
                        && e.args().get(1) instanceof StepEntity.Ref ref) {
                    return ents.get(ref.id());
                }
            }
        }
        return fallback;
    }

    /**
     * DFS from {@code rootId}, including the coords of every CARTESIAN_POINT
     * (skipping style entities that bloat the walk for no geometric value).
     */
    private BoundingBox accumulatePoints(int rootId, Map<Integer, StepEntity> ents) {
        boolean[] seen = new boolean[maxId(ents) + 1];
        double[] acc = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        int[] count = new int[]{0};
        walk(rootId, ents, seen, acc, count);
        if (count[0] == 0) {
            throw new IllegalStateException("No CARTESIAN_POINT reached from shape representation #" + rootId);
        }
        return new BoundingBox(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5]);
    }

    private void walk(int id, Map<Integer, StepEntity> ents, boolean[] seen,
                      double[] acc, int[] count) {
        if (id < 0 || id >= seen.length || seen[id]) return;
        seen[id] = true;
        StepEntity e = ents.get(id);
        if (e == null) return;

        if (e.type().equals("CARTESIAN_POINT")) {
            double[] xyz = readCoordinates(e);
            if (xyz != null) {
                acc[0] = Math.min(acc[0], xyz[0]); acc[1] = Math.min(acc[1], xyz[1]); acc[2] = Math.min(acc[2], xyz[2]);
                acc[3] = Math.max(acc[3], xyz[0]); acc[4] = Math.max(acc[4], xyz[1]); acc[5] = Math.max(acc[5], xyz[2]);
                count[0]++;
            }
            // A CARTESIAN_POINT has no further geometry under it.
            return;
        }
        // Skip pure styling — never carries geometry we care about.
        if (isStyleEntity(e.type())) return;

        for (Object arg : e.args()) {
            if (arg instanceof StepEntity.Ref r) {
                walk(r.id(), ents, seen, acc, count);
            } else if (arg instanceof StepEntity.InlineList list) {
                for (Object item : list.items()) {
                    if (item instanceof StepEntity.Ref r) walk(r.id(), ents, seen, acc, count);
                }
            }
        }
    }

    private static boolean isStyleEntity(String t) {
        return t.startsWith("STYLED_") || t.startsWith("PRESENTATION_") || t.startsWith("FILL_AREA")
                || t.startsWith("SURFACE_STYLE") || t.startsWith("CURVE_STYLE")
                || t.equals("COLOUR_RGB") || t.contains("PRE_DEFINED_CURVE_FONT");
    }

    /**
     * Read the (x, y, z) coordinates of a {@code CARTESIAN_POINT}. STEP allows
     * either flattened args ({@code '', 1.0, 2.0, 3.0}) or an inline list
     * ({@code '', (1.0, 2.0, 3.0)}). Returns null if not exactly 3 finite
     * coordinates can be extracted.
     */
    private static double[] readCoordinates(StepEntity e) {
        double[] out = new double[3];
        int found = 0;
        for (Object arg : e.args()) {
            if (arg instanceof StepEntity.InlineList list) {
                for (Object item : list.items()) {
                    if (item instanceof String s && found < 3) {
                        double v = parseDouble(s);
                        if (Double.isFinite(v)) { out[found++] = v; }
                    }
                }
            } else if (arg instanceof String s && found < 3) {
                double v = parseDouble(s);
                if (Double.isFinite(v)) { out[found++] = v; }
            }
            if (found == 3) break;
        }
        return found == 3 ? out : null;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static int maxId(Map<Integer, StepEntity> ents) {
        int m = 0;
        for (Integer k : ents.keySet()) if (k > m) m = k;
        return m;
    }
}
