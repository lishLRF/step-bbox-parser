package com.cadbbox.parser.web;

import com.cadbbox.parser.bbox.BoundingBox;
import com.cadbbox.parser.bbox.BboxIndexer;
import com.cadbbox.parser.bbox.BoundingBoxCalculator;
import com.cadbbox.parser.step.StepParser;
import com.cadbbox.parser.tree.AssemblyNode;
import com.cadbbox.parser.tree.AssemblyTreeBuilder;
import com.cadbbox.parser.tree.Transform4;
import com.cadbbox.parser.web.dto.BoundingBoxDto;
import com.cadbbox.parser.web.dto.ModelMetadata;
import com.cadbbox.parser.web.dto.NodeType;
import com.cadbbox.parser.web.dto.PartBBox;
import com.cadbbox.parser.web.dto.TreeNode;
import com.cadbbox.parser.web.dto.Vec3;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates parse + cache for uploaded models.
 *
 * <p>Performance model: at upload time we (1) parse the STEP file, (2) build the
 * assembly tree, and (3) build a per-product local-frame AABB index in ONE pass
 * ({@link BboxIndexer}). Tree queries then serialize the cached tree and lift
 * each leaf's local AABB into root coordinates via an 8-corner transform — no
 * per-leaf geometry re-walk, so a 1500-leaf tree serializes in well under a second.
 */
@Service
public class ModelService {

    private final StepParser parser;
    private final AssemblyTreeBuilder treeBuilder;
    private final BoundingBoxCalculator bboxCalc;
    private final BboxIndexer bboxIndexer;
    private final AnnotationStore annotations;
    private final Map<String, ParsedModel> store = new ConcurrentHashMap<>();

    public ModelService(StepParser parser, AssemblyTreeBuilder treeBuilder,
                        BoundingBoxCalculator bboxCalc, BboxIndexer bboxIndexer,
                        AnnotationStore annotations) {
        this.parser = parser;
        this.treeBuilder = treeBuilder;
        this.bboxCalc = bboxCalc;
        this.bboxIndexer = bboxIndexer;
        this.annotations = annotations;
    }

    public ModelMetadata upload(MultipartFile file) throws IOException {
        validate(file);
        StepParser.ParsedStepFile parsed;
        try {
            parsed = parser.parse(file.getInputStream());
        } catch (IOException e) {
            throw new StepParseException("STEP parse failed: " + e.getMessage(), e);
        }
        if (parsed.entities().isEmpty()) {
            throw new StepParseException("No STEP entities found — file is empty or not ISO-10303-21");
        }
        List<AssemblyNode> roots;
        try {
            roots = treeBuilder.build(parsed);
        } catch (RuntimeException e) {
            throw new StepParseException("Assembly tree build failed: " + e.getMessage(), e);
        }
        // Build the per-product AABB index once (the expensive pass); reuse forever.
        Map<Integer, BoundingBox> productBboxes = bboxIndexer.index(parsed);
        String id = UUID.randomUUID().toString();
        store.put(id, new ParsedModel(id, file.getOriginalFilename(), parsed, roots, productBboxes));
        return metadata(id, parsed, file.getOriginalFilename(), roots);
    }

    /** Build the full REST tree (with per-leaf AABBs) for a cached model. */
    public TreeNode tree(String id) {
        ParsedModel m = require(id);
        if (m.roots().isEmpty()) {
            throw new StepParseException("Model has no assembly tree");
        }
        AssemblyNode root = m.roots().get(0);
        AnnotationStore.Annotations ann;
        try { ann = annotations.load(id); } catch (IOException e) { ann = new AnnotationStore.Annotations(); }
        return toTreeNode(root, m, true, ann);
    }

    /** Flat list of every leaf part's bbox (Slice 7 export). */
    public List<PartBBox> bboxList(String id) {
        TreeNode root = tree(id);
        List<PartBBox> out = new ArrayList<>();
        collectLeaves(root, out);
        return out;
    }

    private void collectLeaves(TreeNode n, List<PartBBox> out) {
        if (n.type() == NodeType.PART && n.boundingBox() != null) {
            var b = n.boundingBox();
            out.add(new PartBBox(n.id(), n.name(), n.productLabel(),
                    b.min().x(), b.min().y(), b.min().z(),
                    b.max().x(), b.max().y(), b.max().z(),
                    b.center().x(), b.center().y(), b.center().z(),
                    b.size().x(), b.size().y(), b.size().z()));
        }
        for (TreeNode c : n.children()) collectLeaves(c, out);
    }

    // ---- Slice 6: rename ----
    public void renameNode(String modelId, String nodeId, String newName) throws IOException {
        annotations.rename(modelId, nodeId, newName);
    }

    // ---- Slice 8: merge groups ----
    public String createMergeGroup(String modelId, List<String> memberIds, String name) throws IOException {
        // AABB union of the selected members' boxes.
        TreeNode root = tree(modelId);
        double[] acc = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        Set<String> members = new HashSet<>(memberIds);
        walkForBoxes(root, members, acc);
        if (!Double.isFinite(acc[0])) {
            throw new BadFileException("Selected nodes have no bounding boxes to merge");
        }
        return annotations.addMergeGroup(modelId, memberIds, acc, name);
    }

    public void deleteMergeGroup(String modelId, String groupId) throws IOException {
        annotations.deleteMergeGroup(modelId, groupId);
    }

    public void renameMergeGroup(String modelId, String groupId, String newName) throws IOException {
        annotations.renameMergeGroup(modelId, groupId, newName);
    }

    private void walkForBoxes(TreeNode n, Set<String> members, double[] acc) {
        if (n.boundingBox() != null && members.contains(n.id())) {
            acc[0] = Math.min(acc[0], n.boundingBox().min().x());
            acc[1] = Math.min(acc[1], n.boundingBox().min().y());
            acc[2] = Math.min(acc[2], n.boundingBox().min().z());
            acc[3] = Math.max(acc[3], n.boundingBox().max().x());
            acc[4] = Math.max(acc[4], n.boundingBox().max().y());
            acc[5] = Math.max(acc[5], n.boundingBox().max().z());
        }
        for (TreeNode c : n.children()) walkForBoxes(c, members, acc);
    }

    /** Lift a local-frame AABB into another frame by transforming its 8 corners. */
    private static BoundingBox transformAabb(BoundingBox local, Transform4 t) {
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

    public ModelMetadata metadata(String id) {
        ParsedModel m = require(id);
        return metadata(id, m.parsed(), m.fileName(), m.roots());
    }

    public void delete(String id) {
        store.remove(id);
    }

    // ---- internal ----

    private TreeNode toTreeNode(AssemblyNode node, ParsedModel model, boolean isRoot,
                                AnnotationStore.Annotations ann) {
        NodeType type = node.isAssembly()
                ? (isRoot ? NodeType.ASSEMBLY : NodeType.SUBASSEMBLY)
                : NodeType.PART;
        BoundingBoxDto bboxDto = null;
        if (!node.isAssembly()) {
            // O(1) lookup against the pre-built index, then lift the local AABB's
            // 8 corners into root coordinates via the instance transform chain.
            BoundingBox local = model.productBboxes().get(node.productId());
            if (local != null) {
                BoundingBox b = transformAabb(local, node.rootTransform());
                bboxDto = new BoundingBoxDto(
                        new Vec3(b.minX(), b.minY(), b.minZ()),
                        new Vec3(b.maxX(), b.maxY(), b.maxZ()),
                        new Vec3(b.centerX(), b.centerY(), b.centerZ()),
                        new Vec3(b.sizeX(), b.sizeY(), b.sizeZ()));
            }
        }
        List<TreeNode> children = new ArrayList<>();
        for (AssemblyNode c : node.children()) {
            children.add(toTreeNode(c, model, false, ann));
        }
        String nodeId = nodeId(node, isRoot);
        // Apply persisted rename if any.
        String displayName = ann.renames.getOrDefault(nodeId, node.name());
        // Append merge groups created under this node as virtual SUBASSEMBLY children.
        for (AnnotationStore.MergeGroup g : ann.mergeGroups) {
            // Attach each merge group under the root (simple MVP placement).
            if (isRoot) {
                children.add(new TreeNode(g.id, g.name, g.name, NodeType.SUBASSEMBLY,
                        null, new BoundingBoxDto(
                                new Vec3(g.aabb[0], g.aabb[1], g.aabb[2]),
                                new Vec3(g.aabb[3], g.aabb[4], g.aabb[5]),
                                new Vec3((g.aabb[0] + g.aabb[3]) / 2, (g.aabb[1] + g.aabb[4]) / 2, (g.aabb[2] + g.aabb[5]) / 2),
                                new Vec3(g.aabb[3] - g.aabb[0], g.aabb[4] - g.aabb[1], g.aabb[5] - g.aabb[2])),
                        List.of()));
            }
        }
        return new TreeNode(nodeId, displayName, node.productLabel(), type,
                node.localTransform().m, bboxDto, children);
    }

    private static String nodeId(AssemblyNode node, boolean isRoot) {
        // Stable id per node: combine product id with a counter is hard without
        // a registry; use product id + child-index path via the node's identity.
        // For slice 2 we use productId + System.identityHashCode for uniqueness
        // across instances. Slice 5/6 will assign stable ids when selection lands.
        return (isRoot ? "root" : "p") + "-" + node.productId() + "-"
                + Integer.toHexString(System.identityHashCode(node));
    }

    private ModelMetadata metadata(String id, StepParser.ParsedStepFile parsed, String fileName,
                                   List<AssemblyNode> roots) {
        int parts = 0, assemblies = 0;
        for (AssemblyNode root : roots) {
            int[] counts = count(root);
            parts += counts[0];
            assemblies += counts[1];
        }
        if (parts == 0) parts = 1; // single-part fallback
        return new ModelMetadata(
                id, fileName, parsed.sourceCadSystem(), parsed.schemas(), parsed.unit(),
                Instant.now().toString(), parts, assemblies);
    }

    private int[] count(AssemblyNode n) {
        if (!n.isAssembly()) return new int[]{1, 0};
        int parts = 0, assemblies = 1;
        for (AssemblyNode c : n.children()) {
            int[] sub = count(c);
            parts += sub[0];
            assemblies += sub[1];
        }
        return new int[]{parts, assemblies};
    }

    private ParsedModel require(String id) {
        ParsedModel m = store.get(id);
        if (m == null) throw new ModelNotFoundException(id);
        return m;
    }

    private void validate(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || (!name.toLowerCase().endsWith(".stp") && !name.toLowerCase().endsWith(".step"))) {
            throw new BadFileException("Only .stp / .step files are accepted");
        }
        // Magic-byte check: a STEP file must start with "ISO-10303-21;" (allowing a BOM).
        byte[] head = new byte[16];
        try {
            try (var in = file.getInputStream()) {
                int read = in.read(head);
                if (read < 13) {
                    throw new BadFileException("File too small to be a STEP file");
                }
            }
        } catch (IOException e) {
            throw new BadFileException("Could not read file header: " + e.getMessage());
        }
        String header = new String(head, 0, 13, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        if (!header.startsWith("ISO-10303-21")) {
            throw new BadFileException("Not a STEP file: missing 'ISO-10303-21' magic header");
        }
    }

    public static class StepParseException extends RuntimeException {
        public StepParseException(String msg) { super(msg); }
        public StepParseException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static class BadFileException extends RuntimeException {
        public BadFileException(String msg) { super(msg); }
    }

    public static class ModelNotFoundException extends RuntimeException {
        public ModelNotFoundException(String id) { super("Model not found: " + id); }
    }
}
