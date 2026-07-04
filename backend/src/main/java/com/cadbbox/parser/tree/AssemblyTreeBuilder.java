package com.cadbbox.parser.tree;

import java.util.*;

import com.cadbbox.parser.step.StepEntity;
import com.cadbbox.parser.step.StepNameCodec;
import com.cadbbox.parser.step.StepParser.ParsedStepFile;
import org.springframework.stereotype.Component;

/**
 * Builds the assembly tree from parsed STEP entities.
 *
 * <p>Edge model: {@code NEXT_ASSEMBLY_USAGE_OCCURRENCE} records link a parent
 * {@code PRODUCT_DEFINITION} to a child {@code PRODUCT_DEFINITION}. Each NAUO
 * also carries the child's placement (an {@code AXIS2_PLACEMENT_3D} reference,
 * usually the 6th argument or reachable via the NAUO's descriptor). The same
 * PRODUCT can be instantiated many times — each NAUO is a distinct instance.
 *
 * <p>After building the parent→child DAG, we accumulate each node's root
 * transform by composing local transforms from the leaf up to the root, so the
 * {@link com.cadbbox.parser.bbox.BoundingBoxCalculator} can place every part's
 * geometry in assembly-root coordinates.
 */
@Component
public class AssemblyTreeBuilder {

    /**
     * Build the assembly forest from a parsed STEP file. Returns the root nodes
     * (usually one — the top-level assembly); if no NAUO structure exists,
     * returns a synthetic single root for any part found.
     */
    public List<AssemblyNode> build(ParsedStepFile file) {
        Map<Integer, StepEntity> ents = file.entities();

        // 1. PRODUCT_DEFINITION -> PRODUCT id (via PRODUCT_DEFINITION_FORMATION)
        Map<Integer, Integer> pdToProduct = new HashMap<>();
        // PRODUCT_DEFINITION_FORMATION_WITH_SPECIFIED_SOURCE / PRODUCT_DEFINITION_FORMATION:
        // args = (id, label, #PRODUCT, ?). The PRODUCT ref is arg index 2.
        Map<Integer, Integer> pdfToProduct = new HashMap<>();
        for (StepEntity e : ents.values()) {
            if (e.type().equals("PRODUCT_DEFINITION_FORMATION")
                    || e.type().equals("PRODUCT_DEFINITION_FORMATION_WITH_SPECIFIED_SOURCE")) {
                StepEntity.Ref prodRef = e.refAt(2);
                if (prodRef != null) pdfToProduct.put(e.id(), prodRef.id());
            }
        }
        for (StepEntity e : ents.values()) {
            if (e.type().equals("PRODUCT_DEFINITION")) {
                // args = (id, description, #formation, #frame_of_reference)
                StepEntity.Ref formationRef = e.refAt(2);
                if (formationRef != null) {
                    Integer prod = pdfToProduct.get(formationRef.id());
                    if (prod != null) pdToProduct.put(e.id(), prod);
                }
            }
        }

        // 2. Collect NAUO edges: (parentPD, childPD, placement)
        // NAUO args: (id, name, description, #related (child PD), #relating (parent PD), [placement])
        // Per ISO 10303-43: 'related' = the component being used (child),
        // 'relating' = the assembly it's used in (parent).
        List<Nauo> edges = new ArrayList<>();
        Set<Integer> childPds = new HashSet<>();
        Set<Integer> parentPds = new HashSet<>();
        for (StepEntity e : ents.values()) {
            if (e.type().equals("NEXT_ASSEMBLY_USAGE_OCCURRENCE")) {
                List<StepEntity.Ref> refs = new ArrayList<>();
                for (Object a : e.args()) {
                    if (a instanceof StepEntity.Ref r) refs.add(r);
                }
                if (refs.size() < 2) continue;
                int child = refs.get(0).id();   // related = child component
                int parent = refs.get(1).id();   // relating = parent assembly
                Integer placement = refs.size() >= 3 ? refs.get(2).id() : null;
                edges.add(new Nauo(parent, child, placement));
                parentPds.add(parent);
                childPds.add(child);
            }
        }

        // 3. Find roots: PDs that are parents but never children.
        List<Integer> rootPds = new ArrayList<>();
        for (Integer pd : parentPds) if (!childPds.contains(pd)) rootPds.add(pd);

        // 4. Build the tree recursively, resolving per-instance placement.
        // A given PD may be instantiated under multiple parents → each NAUO edge
        // creates a fresh node with its own placement & subtree.
        Map<Integer, StepEntity> entityMap = ents;
        if (rootPds.isEmpty()) {
            // No assembly structure — synthesize a root from any PRODUCT_DEFINITION.
            for (StepEntity e : ents.values()) {
                if (e.type().equals("PRODUCT_DEFINITION")) {
                    rootPds = List.of(e.id());
                    break;
                }
            }
        }
        // When multiple roots exist, Creo/AP214 files often have a fragmented
        // NAUO topology where the true top-level host isn't reachable as a single
        // root (it's linked via CONTEXT_DEPENDENT_SHAPE_REPRESENTATION, which we
        // don't parse). Rather than pick one arbitrary small subtree, wrap ALL
        // roots under a synthetic "MODEL" node so the user sees everything. Rank
        // by subtree size so the biggest assemblies come first.
        if (rootPds.size() > 1) {
            rootPds.sort((a, b) -> distinctDescendantCount(b, edges) - distinctDescendantCount(a, edges));
        }
        List<AssemblyNode> roots = new ArrayList<>();
        for (int rootPd : rootPds) {
            roots.add(buildSubtree(rootPd, Transform4.IDENTITY, null, edges, pdToProduct, entityMap, new HashSet<>()));
        }
        // If there are many roots, wrap them under one synthetic root for a
        // single, navigable tree. Keep up to 200 to stay usable.
        if (roots.size() > 1) {
            List<AssemblyNode> capped = roots.size() > 200 ? roots.subList(0, 200) : roots;
            AssemblyNode synthetic = new AssemblyNode(
                    -1, "MODEL (" + roots.size() + " sub-assemblies)", "", true,
                    Transform4.IDENTITY, Transform4.IDENTITY, new ArrayList<>(capped));
            return List.of(synthetic);
        }
        return roots;
    }

    /** Count distinct descendant PDs reachable below this PD (root-quality signal). */
    private int distinctDescendantCount(int pdId, List<Nauo> edges) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (Nauo n : edges) if (n.parentPd == pdId) stack.push(n.childPd);
        while (!stack.isEmpty()) {
            int cur = stack.pop();
            if (!seen.add(cur)) continue;
            for (Nauo n : edges) if (n.parentPd == cur) stack.push(n.childPd);
        }
        return seen.size();
    }

    private AssemblyNode buildSubtree(int pdId, Transform4 parentRoot, String instanceName,
                                      List<Nauo> edges, Map<Integer, Integer> pdToProduct,
                                      Map<Integer, StepEntity> ents, Set<String> seen) {
        Integer productId = pdToProduct.get(pdId);
        String productName = productId != null ? productName(ents.get(productId)) : "part";
        // Display name: prefer the per-instance placement name (decoded); fall back
        // to the product name. The productLabel always carries the product name/number.
        String name = instanceName != null && !instanceName.isEmpty() ? instanceName : productName;
        String label = productName;
        // Pre-decode both so the REST layer receives real Unicode.
        name = StepNameCodec.decode(name);
        label = StepNameCodec.decode(label);

        // Find all NAUO edges where this PD is the parent → its children.
        List<Nauo> childEdges = new ArrayList<>();
        for (Nauo n : edges) if (n.parentPd == pdId) childEdges.add(n);
        boolean isAssembly = !childEdges.isEmpty();

        // This node's local transform is IDENTITY for the root; for a child it
        // comes from the NAUO placement (resolved at the child side below).
        // We treat the root placement as IDENTITY.
        AssemblyNode node = new AssemblyNode(productId == null ? -1 : productId, name, label,
                isAssembly, Transform4.IDENTITY, parentRoot, new ArrayList<>());

        String dedupKey = pdId + "@" + System.identityHashCode(parentRoot);
        if (!seen.add(dedupKey)) return node; // cycle guard

        for (Nauo childEdge : childEdges) {
            Transform4 childLocal = resolvePlacement(childEdge.placement, ents);
            String childInstanceName = placementName(childEdge.placement, ents);
            Transform4 childRoot = parentRoot.compose(childLocal);
            AssemblyNode child = buildSubtree(childEdge.childPd, childRoot, childInstanceName, edges, pdToProduct, ents, seen);
            // Override the child's local/root with the per-instance transform.
            node.children().add(new AssemblyNode(
                    child.productId(), child.name(), child.productLabel(), child.isAssembly(),
                    childLocal, childRoot, child.children()));
        }
        return node;
    }

    /** Resolve an {@code AXIS2_PLACEMENT_3D} id → Transform4. */
    private Transform4 resolvePlacement(Integer placementId, Map<Integer, StepEntity> ents) {
        if (placementId == null) return Transform4.IDENTITY;
        StepEntity ax = ents.get(placementId);
        if (ax == null || !ax.type().equals("AXIS2_PLACEMENT_3D")) return Transform4.IDENTITY;
        // args = (name, #location(CARTESIAN_POINT), #axis(DIRECTION), #refDirection(DIRECTION))
        StepEntity.Ref locRef = ax.refAt(1);
        StepEntity.Ref axisRef = ax.refAt(2);
        StepEntity.Ref refDirRef = ax.refAt(3);
        double[] origin = point(locRef, ents);
        double[] axis = direction(axisRef, ents, new double[]{0, 0, 1});
        double[] refDir = direction(refDirRef, ents, new double[]{1, 0, 0});
        return Transform4.fromAxis2Placement(origin, axis, refDir);
    }

    private double[] point(StepEntity.Ref ref, Map<Integer, StepEntity> ents) {
        if (ref == null) return new double[]{0, 0, 0};
        StepEntity cp = ents.get(ref.id());
        if (cp == null) return new double[]{0, 0, 0};
        double[] xyz = readCoords(cp);
        return xyz != null ? xyz : new double[]{0, 0, 0};
    }

    private double[] direction(StepEntity.Ref ref, Map<Integer, StepEntity> ents, double[] fallback) {
        if (ref == null) return fallback;
        StepEntity dir = ents.get(ref.id());
        if (dir == null) return fallback;
        double[] xyz = readCoords(dir);
        return xyz != null ? xyz : fallback;
    }

    /** Read the (x, y, z) triple from a CARTESIAN_POINT or DIRECTION. */
    private static double[] readCoords(StepEntity e) {
        // CARTESIAN_POINT / DIRECTION are always ('', (x, y, z)) — the coords live
        // in the first InlineList argument. Find it and read exactly 3 doubles.
        for (Object arg : e.args()) {
            if (arg instanceof StepEntity.InlineList list) {
                return readThree(list.items());
            }
        }
        // Fallback: some exporters flatten to ('', x, y, z).
        return readThree(e.args().stream().toList());
    }

    private static double[] readThree(java.util.List<Object> items) {
        double[] out = new double[3];
        int found = 0;
        for (Object item : items) {
            if (found == 3) break;
            if (item instanceof String s) {
                try {
                    out[found] = Double.parseDouble(s.trim());
                    found++;
                } catch (NumberFormatException ignored) { /* skip non-numbers like the name */ }
            }
        }
        return found == 3 ? out : null;
    }

    /** The instance name carried by a placement's AXIS2_PLACEMENT_3D (raw, undecoded). */
    private String placementName(Integer placementId, Map<Integer, StepEntity> ents) {
        if (placementId == null) return null;
        StepEntity ax = ents.get(placementId);
        if (ax == null || !ax.type().equals("AXIS2_PLACEMENT_3D")) return null;
        return ax.stringAt(0);
    }

    private String productName(StepEntity productEntity) {
        if (productEntity == null) return "part";
        // PRODUCT args = (id, name, description, ...) — use the name (arg 1) if present,
        // else the id (arg 0). Both may carry \X2\ escapes for Chinese.
        String name = productEntity.stringAt(1);
        if (name != null && !name.isEmpty()) return name;
        String id = productEntity.stringAt(0);
        return (id != null && !id.isEmpty()) ? id : "part";
    }

    private record Nauo(int parentPd, int childPd, Integer placement) {}
}
