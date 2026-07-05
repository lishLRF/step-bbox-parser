package com.cadbbox.parser.bbox;

import com.cadbbox.parser.step.StepEntity;
import com.cadbbox.parser.step.StepParser.ParsedStepFile;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds per-product local-frame AABBs in a SINGLE pass over the parsed entities
 * — the performance-critical path for large Creo assemblies (1500+ leaves).
 *
 * <p>The naive approach (one DFS per leaf's shape representation) is O(leaves ×
 * entities-per-rep) and re-walks shared geometry; on GMC2550WRS it blows past
 * 2 GB and times out. This indexer instead:
 * <ol>
 *   <li>Resolves product → shape-representation via PDS/SDR (one pass).</li>
 *   <li>For each shape rep, runs ONE geometry DFS that accumulates every
 *       reachable {@code CARTESIAN_POINT} into that product's local AABB. A
 *       global visited-set means an entity shared across products is still only
 *       walked once for the points it contributes to <em>each</em> owner — but
 *       within a single owner's walk, dedup keeps it linear.</li>
 * </ol>
 *
 * <p>Result: {@code Map<productId, BoundingBox>} produced in roughly one linear
 * sweep of the entity graph, suitable for instant lookup during tree
 * serialization (no per-leaf re-walk).
 */
@Component
public class BboxIndexer {

    /**
     * Build the product→local-AABB index for a parsed STEP file.
     * Call once per uploaded model; the result is reused for every tree query.
     */
    public Map<Integer, BoundingBox> index(ParsedStepFile file) {
        Map<Integer, StepEntity> ents = file.entities();

        // --- Pass 1: link products to their shape representation ---
        Map<Integer, Integer> pdfToProduct = new HashMap<>();
        Map<Integer, Integer> pdToProduct = new HashMap<>();
        Map<Integer, Integer> pdsToPd = new HashMap<>();
        List<int[]> sdrs = new ArrayList<>();          // (pdsId, repId)
        for (StepEntity e : ents.values()) {
            switch (e.type()) {
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
        // product → its shape-rep entity (first SDR wins; products rarely have >1).
        Map<Integer, StepEntity> productToRep = new HashMap<>();
        for (int[] sdr : sdrs) {
            Integer pd = pdsToPd.get(sdr[0]);
            if (pd == null) continue;
            Integer product = pdToProduct.get(pd);
            if (product == null) continue;
            StepEntity rep = ents.get(sdr[1]);
            if (rep != null) productToRep.putIfAbsent(product, rep);
        }

        // --- Pass 2: for each product's rep, ONE DFS accumulating points ---
        // IMPORTANT: each product gets its OWN visited set. A global set would
        // "eat" shared entities (placement axes, edge curves) on the first
        // product, starving all later products of their geometry. With per-
        // product sets, the same entity may be walked multiple times (once per
        // owning product), but every product reliably collects ALL its points.
        Map<Integer, BoundingBox> result = new HashMap<>();

        for (Map.Entry<Integer, StepEntity> entry : productToRep.entrySet()) {
            int productId = entry.getKey();
            StepEntity rep = entry.getValue();
            double[] acc = new double[]{
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            int[] count = new int[]{0};
            // Per-product visited set — no cross-product sharing.
            Set<Integer> localSeen = new HashSet<>();
            walkPoints(rep.id(), ents, localSeen, acc, count);
            if (count[0] > 0) {
                result.put(productId, new BoundingBox(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5]));
            }
        }
        return result;
    }

    /** DFS from {@code rootId}, folding every CARTESIAN_POINT's coords into acc. */
    private void walkPoints(int rootId, Map<Integer, StepEntity> ents, Set<Integer> seen,
                            double[] acc, int[] count) {
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            int id = stack.pop();
            if (!seen.add(id)) continue;     // already visited in this walk
            StepEntity e = ents.get(id);
            if (e == null) continue;

            if (e.type().equals("CARTESIAN_POINT")) {
                double[] xyz = readCoords(e);
                if (xyz != null) {
                    acc[0] = Math.min(acc[0], xyz[0]); acc[1] = Math.min(acc[1], xyz[1]); acc[2] = Math.min(acc[2], xyz[2]);
                    acc[3] = Math.max(acc[3], xyz[0]); acc[4] = Math.max(acc[4], xyz[1]); acc[5] = Math.max(acc[5], xyz[2]);
                    count[0]++;
                }
                continue;  // points have no further geometry under them
            }
            if (isStyleEntity(e.type())) continue;

            // Push child refs (and inline-list refs) for traversal.
            for (Object arg : e.args()) {
                if (arg instanceof StepEntity.Ref r) {
                    stack.push(r.id());
                } else if (arg instanceof StepEntity.InlineList list) {
                    for (Object item : list.items()) {
                        if (item instanceof StepEntity.Ref r) stack.push(r.id());
                    }
                }
            }
        }
    }

    private static boolean isStyleEntity(String t) {
        return t.startsWith("STYLED_") || t.startsWith("PRESENTATION_") || t.startsWith("FILL_AREA")
                || t.startsWith("SURFACE_STYLE") || t.startsWith("CURVE_STYLE")
                || t.equals("COLOUR_RGB") || t.contains("PRE_DEFINED_CURVE_FONT");
    }

    /** Read (x,y,z) from a CARTESIAN_POINT — accepts both flattened and inline-list forms. */
    private static double[] readCoords(StepEntity e) {
        double[] out = new double[3];
        int found = 0;
        for (Object arg : e.args()) {
            if (arg instanceof StepEntity.InlineList list) {
                for (Object item : list.items()) {
                    if (item instanceof String s && found < 3) {
                        try { out[found++] = Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
                    }
                }
            } else if (arg instanceof String s && found < 3) {
                try { out[found++] = Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
            }
            if (found == 3) break;
        }
        return found == 3 ? out : null;
    }
}
