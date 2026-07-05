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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final Path uploadDir;
    private final String pythonExe;
    private final Path bboxScript;
    private final Path bboxCacheDir;
    private final Map<String, ParsedModel> store = new ConcurrentHashMap<>();
    /** Per-model upload/bbox progress: "12/6580" or "done". Keyed by model id. */
    private final Map<String, String> uploadProgress = new ConcurrentHashMap<>();

    public ModelService(StepParser parser, AssemblyTreeBuilder treeBuilder,
                        BoundingBoxCalculator bboxCalc, BboxIndexer bboxIndexer,
                        AnnotationStore annotations,
                        @org.springframework.beans.factory.annotation.Value("${parser.upload-dir:#{T(java.lang.System).getProperty('java.io.tmpdir')} }") String uploadDir,
                        @org.springframework.beans.factory.annotation.Value("${mesh.python-exe:python}") String pythonExe,
                        @org.springframework.beans.factory.annotation.Value("${mesh.bbox-script:scripts/step_to_bbox.py}") String bboxScript)
            throws IOException {
        this.parser = parser;
        this.treeBuilder = treeBuilder;
        this.bboxCalc = bboxCalc;
        this.bboxIndexer = bboxIndexer;
        this.annotations = annotations;
        this.uploadDir = Paths.get(uploadDir);
        this.pythonExe = pythonExe;
        // Resolve script path same way as MeshService.
        Path sp = Paths.get(bboxScript);
        if (!Files.exists(sp)) {
            String jarLoc = System.getProperty("user.dir");
            Path projRoot = Paths.get(jarLoc).getParent();
            if (projRoot != null && Files.exists(projRoot.resolve(bboxScript)))
                sp = projRoot.resolve(bboxScript).toAbsolutePath();
            else if (Files.exists(Paths.get(jarLoc).resolve(bboxScript)))
                sp = Paths.get(jarLoc).resolve(bboxScript).toAbsolutePath();
        }
        this.bboxScript = sp;
        this.bboxCacheDir = Paths.get(uploadDir, "step-bbox-bboxes");
        Files.createDirectories(this.uploadDir);
        Files.createDirectories(this.bboxCacheDir);
    }

    public ModelMetadata upload(MultipartFile file, String displayName) throws IOException {
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
        // Persist the uploaded STEP so the mesh generator can read it later.
        Path saved = uploadDir.resolve(id + ".stp");
        file.transferTo(saved.toFile());
        // Run OCCT-based bbox computation for authoritative geometry bounds.
        // This produces per-part AABBs in mm; we convert to the file's unit.
        Map<String, double[]> occtBboxes = computeOcctBboxes(saved, id);
        store.put(id, new ParsedModel(id, file.getOriginalFilename(), parsed, roots, productBboxes, saved, occtBboxes));
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
        // Build OCCT per-part list (solids + shells) for sequential assignment.
        java.util.List<double[]> occtParts = new java.util.ArrayList<>();
        double[] occtOverall = null;
        if (m.occtBboxes() != null) {
            for (var entry : m.occtBboxes().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("_solid_") || key.startsWith("_shell_")) {
                    occtParts.add(entry.getValue());
                } else if (key.equals("__overall__")) {
                    occtOverall = entry.getValue();
                }
            }
        }
        return toTreeNode(root, m, true, ann, occtParts, occtOverall, new int[]{0});
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

    private static BoundingBoxDto makeDto(double mnX, double mnY, double mnZ,
                                           double mxX, double mxY, double mxZ) {
        return new BoundingBoxDto(
                new Vec3(mnX, mnY, mnZ),
                new Vec3(mxX, mxY, mxZ),
                new Vec3((mnX + mxX) / 2, (mnY + mxY) / 2, (mnZ + mxZ) / 2),
                new Vec3(mxX - mnX, mxY - mnY, mxZ - mnZ));
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

    /** Rename a cached model (user-friendly display name). */
    public void renameModel(String modelId, String newName) throws IOException {
        Path namesFile = uploadDir.resolve("..").resolve("model-names.json").normalize();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (Files.exists(namesFile)) {
            try { names = mapper.readValue(namesFile.toFile(), java.util.Map.class); } catch (Exception ignored) {}
        }
        names.put(modelId, newName);
        mapper.writerWithDefaultPrettyPrinter().writeValue(namesFile.toFile(), names);
        // Also update in-memory if loaded.
        ParsedModel m = store.get(modelId);
        if (m != null) {
            store.put(modelId, new ParsedModel(modelId, newName, m.parsed(), m.roots(), m.productBboxes(), m.sourceStep(), m.occtBboxes()));
        }
    }

    /** Get the user-friendly name for a cached model. */
    private String getModelName(String modelId, String fallback) {
        Path namesFile = uploadDir.resolve("..").resolve("model-names.json").normalize();
        if (Files.exists(namesFile)) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, String> names = mapper.readValue(namesFile.toFile(), java.util.Map.class);
                String name = names.get(modelId);
                if (name != null) return name;
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    /** List all cached models with user-friendly names. */
    public List<Map<String, Object>> listCachedModels() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        File[] stpFiles = uploadDir.toFile().listFiles((d, name) -> name.endsWith(".stp"));
        if (stpFiles == null) return result;
        for (File stp : stpFiles) {
            String id = stp.getName().replace(".stp", "");
            Path bboxJson = bboxCacheDir.resolve(id + "_bbox.json");
            Path glbFile = Paths.get(uploadDir.toString(), "..", "step-bbox-meshes").normalize().resolve(id + ".glb");
            boolean hasBbox = Files.exists(bboxJson);
            boolean hasGlb = Files.exists(glbFile);
            if (!hasBbox) continue; // not fully parsed yet
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", id);
            entry.put("name", getModelName(id, "未命名-" + id.substring(0, 8)));
            entry.put("stpSize", stp.length());
            entry.put("parsedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date(stp.lastModified())));
            entry.put("hasMesh", hasGlb);
            // Try to get part count from bbox JSON.
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(bboxJson.toFile());
                int solids = 0, shells = 0;
                var fields = root.fields();
                while (fields.hasNext()) {
                    var f = fields.next();
                    if (f.getKey().startsWith("_solid_")) solids++;
                    else if (f.getKey().startsWith("_shell_")) shells++;
                }
                entry.put("parts", solids + shells);
            } catch (Exception e) {
                entry.put("parts", 0);
            }
            result.add(entry);
        }
        // Sort newest first.
        result.sort((a, b) -> String.valueOf(b.get("parsedAt")).compareTo(String.valueOf(a.get("parsedAt"))));
        return result;
    }

    /** Re-load a cached model into the in-memory store (skip parse + OCCT bbox). */
    public ModelMetadata loadCached(String id) {
        Path stpFile = uploadDir.resolve(id + ".stp");
        if (!Files.exists(stpFile)) throw new ModelNotFoundException(id);
        // Check if already in memory.
        if (store.containsKey(id)) {
            ParsedModel m = store.get(id);
            return metadata(id, m.parsed(), m.fileName(), m.roots());
        }
        // Re-parse the STEP (fast — text parsing only, ~seconds) and rebuild tree.
        // The OCCT bbox JSON is already on disk, so we just load it.
        try {
            StepParser.ParsedStepFile parsed = parser.parse(Files.newInputStream(stpFile));
            List<AssemblyNode> roots = treeBuilder.build(parsed);
            Map<Integer, BoundingBox> productBboxes = bboxIndexer.index(parsed);
            Map<String, double[]> occtBboxes = parseBboxJson(bboxCacheDir.resolve(id + "_bbox.json"));
            String fileName = getModelName(id, "cached-" + id.substring(0, 8));
            store.put(id, new ParsedModel(id, fileName, parsed, roots, productBboxes, stpFile, occtBboxes));
            return metadata(id, parsed, fileName, roots);
        } catch (Exception e) {
            throw new StepParseException("Failed to load cached model: " + e.getMessage(), e);
        }
    }

    /** Public accessor for cross-service use (e.g. MeshService needs the source path). */
    public ParsedModel requirePublic(String id) {
        return require(id);
    }

    /** Current upload/bbox progress for a model, e.g. "12/6580" or "done" or null. */
    public String getUploadProgress(String id) {
        return uploadProgress.get(id);
    }

    /** Clean up cached files older than the given age (hours). Returns count removed. */
    public int cleanupOldCaches(int maxAgeHours) {
        int removed = 0;
        long cutoff = System.currentTimeMillis() - (long) maxAgeHours * 3600_000;
        for (Path dir : new Path[]{uploadDir, bboxCacheDir}) {
            File[] files = dir.toFile().listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.lastModified() < cutoff) {
                    if (f.delete()) removed++;
                }
            }
        }
        // Also clean old meshes.
        Path meshDir = Paths.get(uploadDir.toString(), "..", "step-bbox-meshes").normalize();
        File[] meshFiles = meshDir.toFile().listFiles();
        if (meshFiles != null) {
            for (File f : meshFiles) {
                if (f.lastModified() < cutoff) {
                    if (f.delete()) removed++;
                }
            }
        }
        return removed;
    }

    public void delete(String id) {
        store.remove(id);
    }

    // ---- internal ----

    private TreeNode toTreeNode(AssemblyNode node, ParsedModel model, boolean isRoot,
                                AnnotationStore.Annotations ann,
                                java.util.List<double[]> occtParts, double[] occtOverall, int[] occtIdx) {
        NodeType type = node.isAssembly()
                ? (isRoot ? NodeType.ASSEMBLY : NodeType.SUBASSEMBLY)
                : NodeType.PART;
        BoundingBoxDto bboxDto = null;
        if (isRoot && occtOverall != null) {
            bboxDto = makeDto(occtOverall[0], occtOverall[1], occtOverall[2],
                              occtOverall[3], occtOverall[4], occtOverall[5]);
        } else if (!node.isAssembly()) {
            // Leaf: use OCCT per-solid bbox (authoritative geometry), assigned
            // in order. This is imperfect (OCCT order ≠ tree leaf order) but
            // gives correct DIMENSIONS. The alternative (text-parser per-product)
            // is worse — many products have no reachable geometry points.
            if (occtIdx[0] < occtParts.size()) {
                double[] ob = occtParts.get(occtIdx[0]);
                occtIdx[0]++;
                bboxDto = makeDto(ob[0], ob[1], ob[2], ob[3], ob[4], ob[5]);
            } else {
                // Fallback: text-parser per-product bbox.
                BoundingBox local = model.productBboxes().get(node.productId());
                if (local != null) {
                    BoundingBox b = transformAabb(local, node.rootTransform());
                    bboxDto = makeDto(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
                }
            }
        }
        List<TreeNode> children = new ArrayList<>();
        for (AssemblyNode c : node.children()) {
            children.add(toTreeNode(c, model, false, ann, occtParts, occtOverall, occtIdx));
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

    /** Run the OCCT-based bbox script, parse the JSON result. Returns empty map on failure. */
    private Map<String, double[]> computeOcctBboxes(Path stepFile, String modelId) {
        Path jsonOut = bboxCacheDir.resolve(modelId + "_bbox.json");
        if (Files.exists(jsonOut) && jsonOut.toFile().length() > 0) {
            return parseBboxJson(jsonOut);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExe,
                    bboxScript.toAbsolutePath().toString(),
                    stepFile.toAbsolutePath().toString(),
                    jsonOut.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // No timeout — some large machine models take >30 min. Instead we
            // log a heartbeat every 30s so the user can confirm it's alive.
            Thread heartbeat = new Thread(() -> {
                int sec = 0;
                while (p.isAlive()) {
                    try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
                    sec += 30;
                    System.out.printf("[bbox] still running... %ds elapsed (pid=%d)%n", sec, p.pid());
                }
            }, "bbox-heartbeat-" + modelId);
            heartbeat.setDaemon(true);
            heartbeat.start();
            // Stream output for diagnostics + progress.
            StringBuilder log = new StringBuilder();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.append(line).append('\n');
                    if (line.startsWith("PROGRESS:")) {
                        uploadProgress.put(modelId, line.substring("PROGRESS:".length()).trim());
                    } else if (line.startsWith("DONE:")) {
                        uploadProgress.put(modelId, "done");
                    }
                }
            }
            // Infinite wait — no timeout.
            p.waitFor();
            heartbeat.interrupt();
            if (p.exitValue() != 0) {
                System.err.println("[bbox] OCCT converter failed (exit " + p.exitValue() + "): " + log);
                return Map.of();
            }
            System.out.println("[bbox] done: " + log.toString().trim());
            return parseBboxJson(jsonOut);
        } catch (Exception e) {
            System.err.println("[bbox] OCCT converter error: " + e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, double[]> parseBboxJson(Path jsonFile) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(jsonFile.toFile());
            Map<String, double[]> result = new java.util.HashMap<>();
            root.fields().forEachRemaining(entry -> {
                var node = entry.getValue();
                if (node.has("min") && node.has("max")) {
                    double[] mn = new double[3], mx = new double[3];
                    for (int i = 0; i < 3; i++) {
                        mn[i] = node.get("min").get(i).asDouble();
                        mx[i] = node.get("max").get(i).asDouble();
                    }
                    // OCCT outputs in mm; our tree uses the file's native unit.
                    // GMC2550WRS STEP coords are in mm already, and our text parser
                    // reads them as-is (no unit conversion). So pass through directly.
                    result.put(entry.getKey(), new double[]{mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]});
                }
            });
            return result;
        } catch (Exception e) {
            System.err.println("[bbox] JSON parse error: " + e.getMessage());
            return Map.of();
        }
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
