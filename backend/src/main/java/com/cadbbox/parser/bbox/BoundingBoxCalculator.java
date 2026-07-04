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

    /** Cache: product id → its local-frame AABB (geometry is shared across instances). */
    private final Map<Integer, BoundingBox> productBboxCache = new HashMap<>();
    private ParsedStepFile cachedFile;
    /** Index: product id → its shape representation entity (built once per file). */
    private Map<Integer, StepEntity> productToShapeRep;

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
     * accumulated transform to every geometry point. The part's local-frame
     * bbox is cached per product id (geometry is shared across instances of the
     * same product), so repeated instances don't re-walk the entity graph.
     *
     * <p>The shape representation is resolved from the part's
     * {@code PRODUCT_DEFINITION} via {@code PRODUCT_DEFINITION_SHAPE.definition}
     * → {@code SHAPE_DEFINITION_REPRESENTATION}, which is robust to Creo's
     * non-standard formation chain.
     */
    public BoundingBox computeForLeaf(ParsedStepFile file, AssemblyNode leaf) {
        // Cache invalidation when the file changes: clear caches and rebuild the
        // product→shape-rep index in one O(N) pass instead of O(N) per product.
        if (cachedFile != file) {
            productBboxCache.clear();
            productToShapeRep = buildProductToShapeRepIndex(file);
            cachedFile = file;
        }
        int productId = leaf.productId();
        if (productId < 0) return null;
        BoundingBox local = productBboxCache.get(productId);
        if (local == null) {
            if (productBboxCache.containsKey(productId)) return null; // known "no geometry"
            StepEntity shapeRep = productToShapeRep.get(productId);
            if (shapeRep == null) {
                productBboxCache.put(productId, null);
                return null;
            }
            try {
                local = accumulate(shapeRep.id(), file.entities(), Transform4.IDENTITY);
            } catch (IllegalStateException e) {
                productBboxCache.put(productId, null);
                return null;
            }
            productBboxCache.put(productId, local);
        }
        if (local == null) return null;
        // Transform the local-frame AABB's 8 corners into root coordinates.
        Transform4 t = leaf.rootTransform();
        double mnX = Double.POSITIVE_INFINITY, mnY = Double.POSITIVE_INFINITY, mnZ = Double.POSITIVE_INFINITY;
        double mxX = Double.NEGATIVE_INFINITY, mxY = Double.NEGATIVE_INFINITY, mxZ = Double.NEGATIVE_INFINITY;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            double x = dx == 0 ? local.minX() : local.maxX();
            double y = dy == 0 ? local.minY() : local.maxY();
            double z = dz == 0 ? local.minZ() : local.maxZ();
            double[] p = t.apply(x, y, z);
            mnX = Math.min(mnX, p[0]); mnY = Math.min(mnY, p[1]); mnZ = Math.min(mnZ, p[2]);
            mxX = Math.max(mxX, p[0]); mxY = Math.max(mxY, p[1]); mxZ = Math.max(mxZ, p[2]);
        }
        return new BoundingBox(mnX, mnY, mnZ, mxX, mxY, mxZ);
    }

    /**
     * One-pass index: product id → shape representation entity. Walks every
     * entity exactly once, building PD→PRODUCT and PDS→PD→PRODUCT and
     * SDR(PDS,rep) maps in a single scan, so per-leaf lookups are O(1).
     */
    private static Map<Integer, StepEntity> buildProductToShapeRepIndex(ParsedStepFile file) {
        Map<Integer, StepEntity> ents = file.entities();
        Map<Integer, StepEntity> result = new HashMap<>();
        // Pass 1: collect formations→product, PDs, PDSs, SDRs in one loop.
        Map<Integer, Integer> pdfToProduct = new HashMap<>();
        Map<Integer, Integer> pdToProduct = new HashMap<>();
        Map<Integer, Integer> pdsToPd = new HashMap<>();          // PDS id → its definition PD
        java.util.List<int[]> sdrs = new java.util.ArrayList<>(); // (pdsId, repId)
        for (StepEntity e : ents.values()) {
            String t = e.type();
            switch (t) {
                case "PRODUCT_DEFINITION_FORMATION",
                     "PRODUCT_DEFINITION_FORMATION_WITH_SPECIFIED_SOURCE" -> {
                    StepEntity.Ref prodRef = e.refAt(2);
                    if (prodRef != null) pdfToProduct.put(e.id(), prodRef.id());
                }
                case "PRODUCT_DEFINITION" -> {
                    for (Object a : e.args()) {
                        if (a instanceof StepEntity.Ref r) {
                            Integer prod = pdfToProduct.get(r.id());
                            if (prod != null) { pdToProduct.put(e.id(), prod); break; }
                        }
                    }
                }
                case "PRODUCT_DEFINITION_SHAPE" -> {
                    // Last ref in args is the .definition (a PD).
                    StepEntity.Ref defRef = null;
                    for (Object a : e.args()) if (a instanceof StepEntity.Ref r) defRef = r;
                    if (defRef != null) pdsToPd.put(e.id(), defRef.id());
                }
                case "SHAPE_DEFINITION_REPRESENTATION" -> {
                    if (e.args().size() >= 2
                            && e.args().get(0) instanceof StepEntity.Ref pdsRef
                            && e.args().get(1) instanceof StepEntity.Ref repRef) {
                        sdrs.add(new int[]{pdsRef.id(), repRef.id()});
                    }
                }
                default -> { }
            }
        }
        // Pass 2: link SDRs → product via PDS→PD→PRODUCT.
        for (int[] sdr : sdrs) {
            Integer pd = pdsToPd.get(sdr[0]);
            if (pd == null) continue;
            Integer product = pdToProduct.get(pd);
            if (product == null) continue;
            StepEntity rep = ents.get(sdr[1]);
            if (rep != null) result.putIfAbsent(product, rep);
        }
        return result;
    }

    /** (Per-product shape-rep lookup is now done via {@link #buildProductToShapeRepIndex}.) */

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
