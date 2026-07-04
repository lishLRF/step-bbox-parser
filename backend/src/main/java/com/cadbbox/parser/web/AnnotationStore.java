package com.cadbbox.parser.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Per-model annotation store: renames and merge groups, persisted as one JSON
 * file per model under a work directory. Shared by Slice 6 (rename) and
 * Slice 8 (merge groups).
 *
 * <p>Storage layout: {@code <workDir>/<modelId>/annotations.json}.
 */
@Component
public class AnnotationStore {

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnnotationStore(@Value("${parser.work-dir:#{T(java.lang.System).getProperty('java.io.tmpdir')} }") String workDir) {
        this.root = Paths.get(workDir, "step-bbox-annotations");
        try { Files.createDirectories(root); } catch (IOException e) { /* best effort */ }
    }

    /** Rename a node (id-stable). Idempotent. */
    public synchronized void rename(String modelId, String nodeId, String newName) throws IOException {
        Annotations a = load(modelId);
        a.renames.put(nodeId, newName);
        save(modelId, a);
    }

    /** Add a merge group; returns its assigned id. */
    public synchronized String addMergeGroup(String modelId, List<String> memberIds, double[] aabb,
                                              String name) throws IOException {
        Annotations a = load(modelId);
        String id = "merge-" + (a.mergeGroups.size() + 1);
        a.mergeGroups.add(new MergeGroup(id, name != null ? name : "合并组 " + a.mergeGroups.size(),
                memberIds, aabb));
        save(modelId, a);
        return id;
    }

    public synchronized void deleteMergeGroup(String modelId, String groupId) throws IOException {
        Annotations a = load(modelId);
        a.mergeGroups.removeIf(g -> g.id.equals(groupId));
        save(modelId, a);
    }

    public synchronized void renameMergeGroup(String modelId, String groupId, String newName) throws IOException {
        Annotations a = load(modelId);
        for (MergeGroup g : a.mergeGroups) if (g.id.equals(groupId)) g.name = newName;
        save(modelId, a);
    }

    public Annotations load(String modelId) throws IOException {
        Path f = path(modelId);
        if (!Files.exists(f)) return new Annotations();
        return mapper.readValue(f.toFile(), Annotations.class);
    }

    private void save(String modelId, Annotations a) throws IOException {
        Path f = path(modelId);
        Files.createDirectories(f.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), a);
    }

    private Path path(String modelId) {
        return root.resolve(modelId).resolve("annotations.json");
    }

    /** Persisted annotations for a model. */
    public static class Annotations {
        public Map<String, String> renames = new LinkedHashMap<>();
        public List<MergeGroup> mergeGroups = new ArrayList<>();
    }

    public static class MergeGroup {
        public String id;
        public String name;
        public List<String> memberIds;
        /** AABB in root coordinates: [minX,minY,minZ,maxX,maxY,maxZ]. */
        public double[] aabb;

        public MergeGroup() {}
        public MergeGroup(String id, String name, List<String> memberIds, double[] aabb) {
            this.id = id; this.name = name; this.memberIds = memberIds; this.aabb = aabb;
        }
    }
}
