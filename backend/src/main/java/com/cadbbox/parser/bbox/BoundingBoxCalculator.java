package com.cadbbox.parser.bbox;

import com.cadbbox.parser.step.StepEntity;
import com.cadbbox.parser.step.StepParser.ParsedStepFile;
import com.cadbbox.parser.tree.AssemblyNode;
import com.cadbbox.parser.tree.Transform4;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes a tight axis-aligned bounding box for each leaf part in the assembly
 * tree, in assembly-root coordinates.
 *
 * <p>Strategy: for each leaf {@link AssemblyNode}, locate its shape
 * representation, DFS-walk every referenced {@code CARTESIAN_POINT}, and apply
 * the leaf's accumulated {@code rootTransform} to each point before folding it
 * into the AABB. Non-leaf (assembly) nodes get a null bbox in the REST tree.
 */
@Component
public class BoundingBoxCalculator {

    /**
     * Compute the AABB of a single part given its shape representation entity,
     * with no transform (Slice 1 path — used by the single-part golden sample).
     */
    public BoundingBox computeForSinglePart(ParsedStepFile file) {
        Map<Integer, StepEntity> ents = file.entities();
        StepEntity shapeRep = findShapeRepresentation(ents);
        if (shapeRep == null) {
            throw new IllegalStateException("No shape representation found in STEP file");
        }
        return accumulate(shapeRep.id(), ents, Transform4.IDENTITY);
    }

    /**
     * Compute a leaf part's AABB in root coordinates, applying the given
     * accumulated transform to every geometry point.
     */
    public BoundingBox computeForLeaf(ParsedStepFile file, AssemblyNode leaf) {
        StepEntity shapeRep = findShapeRepresentationForProduct(file, leaf.productId());
        if (shapeRep == null) return null; // leaf has no geometry (rare)
        return accumulate(shapeRep.id(), file.entities(), leaf.rootTransform());
    }

    /** Find the SHAPE_DEFINITION_REPRESENTATION rep for a PRODUCT id. */
    private StepEntity findShapeRepresentationForProduct(ParsedStepFile file, int productId) {
        if (productId < 0) return null;
        Map<Integer, StepEntity> ents = file.entities();
        // PRODUCT_DEFINITION_SHAPE 'name','desc',#definition → definition is a PD whose formation → PRODUCT
        // Simpler approach: SHAPE_DEFINITION_REPRESENTATION links (#PRODUCT_DEFINITION_SHAPE, #rep).
        // PRODUCT_DEFINITION_SHAPE's 3rd ref points at the PRODUCT_DEFINITION.
        // Walk: find PDS whose .definition PD has formation→PRODUCT == productId.
        // To keep it cheap, precompute a map product -> rep on demand.
        Map<Integer, Integer> pdToProduct = new HashMap<>();
        for (StepEntity e : ents.values()) {
            if (e.type().equals("PRODUCT_DEFINITION_FORMATION")
                    || e.type().equals("PRODUCT_DEFINITION_FORMATION_WITH_SPECIFIED_SOURCE")) {
                StepEntity.Ref prodRef = e.refAt(2);
                if (prodRef != null) pdToProduct.put(e.id(), prodRef.id());
            }
        }
        Map<Integer, Integer> pdToProd = new HashMap<>();
        for (StepEntity e : ents.values()) {
            if (e.type().equals("PRODUCT_DEFINITION")) {
                StepEntity.Ref f = e.refAt(2);
                if (f != null && pdToProduct.containsKey(f.id())) {
                    pdToProd.put(e.id(), pdToProduct.get(f.id()));
                }
            }
        }
        for (StepEntity e : ents.values()) {
            if (e.type().equals("PRODUCT_DEFINITION_SHAPE")) {
                StepEntity.Ref defRef = null;
                for (Object a : e.args()) {
                    if (a instanceof StepEntity.Ref r) defRef = r;
                }
                if (defRef != null && pdToProd.get(defRef.id()) != null
                        && pdToProd.get(defRef.id()) == productId) {
                    // Find SHAPE_DEFINITION_REPRESENTATION referencing this PDS.
                    for (StepEntity sdr : ents.values()) {
                        if (sdr.type().equals("SHAPE_DEFINITION_REPRESENTATION") && sdr.args().size() >= 2
                                && sdr.args().get(0) instanceof StepEntity.Ref r && r.id() == e.id()
                                && sdr.args().get(1) instanceof StepEntity.Ref repRef) {
                            return ents.get(repRef.id());
                        }
                    }
                }
            }
        }
        return null;
    }

    private StepEntity findShapeRepresentation(Map<Integer, StepEntity> ents) {
        StepEntity fallback = null;
        for (StepEntity e : ents.values()) {
            String t = e.type();
            if (t.endsWith("_SHAPE_REPRESENTATION") || t.endsWith("_BREP")
                    || t.startsWith("MANIFOLD_") || t.equals("ADVANCED_BREP_SHAPE_REPRESENTATION")) {
                if (t.endsWith("_REPRESENTATION")) return e;
                if (fallback == null) fallback = e;
            }
        }
        return fallback;
    }

    /** DFS from {@code rootId}, applying {@code transform} to every point. */
    private BoundingBox accumulate(int rootId, Map<Integer, StepEntity> ents, Transform4 transform) {
        boolean[] seen = new boolean[maxId(ents) + 1];
        double[] acc = new double[]{
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        int[] count = new int[]{0};
        walk(rootId, ents, seen, acc, count, transform);
        if (count[0] == 0) {
            throw new IllegalStateException("No CARTESIAN_POINT reached from shape representation #" + rootId);
        }
        return new BoundingBox(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5]);
    }

    private void walk(int id, Map<Integer, StepEntity> ents, boolean[] seen,
                      double[] acc, int[] count, Transform4 transform) {
        if (id < 0 || id >= seen.length || seen[id]) return;
        seen[id] = true;
        StepEntity e = ents.get(id);
        if (e == null) return;

        if (e.type().equals("CARTESIAN_POINT")) {
            double[] xyz = readCoordinates(e);
            if (xyz != null) {
                double[] p = transform.apply(xyz[0], xyz[1], xyz[2]);
                acc[0] = Math.min(acc[0], p[0]); acc[1] = Math.min(acc[1], p[1]); acc[2] = Math.min(acc[2], p[2]);
                acc[3] = Math.max(acc[3], p[0]); acc[4] = Math.max(acc[4], p[1]); acc[5] = Math.max(acc[5], p[2]);
                count[0]++;
            }
            return;
        }
        if (isStyleEntity(e.type())) return;

        for (Object arg : e.args()) {
            if (arg instanceof StepEntity.Ref r) {
                walk(r.id(), ents, seen, acc, count, transform);
            } else if (arg instanceof StepEntity.InlineList list) {
                for (Object item : list.items()) {
                    if (item instanceof StepEntity.Ref r) walk(r.id(), ents, seen, acc, count, transform);
                }
            }
        }
    }

    private static boolean isStyleEntity(String t) {
        return t.startsWith("STYLED_") || t.startsWith("PRESENTATION_") || t.startsWith("FILL_AREA")
                || t.startsWith("SURFACE_STYLE") || t.startsWith("CURVE_STYLE")
                || t.equals("COLOUR_RGB") || t.contains("PRE_DEFINED_CURVE_FONT");
    }

    private static double[] readCoordinates(StepEntity e) {
        double[] out = new double[3];
        int found = 0;
        for (Object arg : e.args()) {
            if (arg instanceof StepEntity.InlineList list) {
                for (Object item : list.items()) {
                    if (item instanceof String s && found < 3) {
                        double v = parseDouble(s);
                        if (Double.isFinite(v)) out[found++] = v;
                    }
                }
            } else if (arg instanceof String s && found < 3) {
                double v = parseDouble(s);
                if (Double.isFinite(v)) out[found++] = v;
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
