package com.cadbbox.parser.web;

import com.cadbbox.parser.bbox.BoundingBox;
import com.cadbbox.parser.bbox.BoundingBoxCalculator;
import com.cadbbox.parser.step.StepParser;
import com.cadbbox.parser.web.dto.BoundingBoxDto;
import com.cadbbox.parser.web.dto.ModelMetadata;
import com.cadbbox.parser.web.dto.NodeType;
import com.cadbbox.parser.web.dto.TreeNode;
import com.cadbbox.parser.web.dto.Vec3;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates parse + cache for uploaded models.
 *
 * <p>Slice 1 behavior: parse synchronously, compute one leaf part's AABB,
 * cache the result keyed by an opaque id, expose metadata + a single-node tree.
 */
@Service
public class ModelService {

    private final StepParser parser;
    private final BoundingBoxCalculator bboxCalc;
    private final Map<String, ParsedModel> store = new ConcurrentHashMap<>();

    public ModelService(StepParser parser, BoundingBoxCalculator bboxCalc) {
        this.parser = parser;
        this.bboxCalc = bboxCalc;
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
        BoundingBox bbox;
        try {
            bbox = bboxCalc.computeForSinglePart(parsed);
        } catch (IllegalStateException e) {
            throw new StepParseException(e.getMessage(), e);
        }
        String id = UUID.randomUUID().toString();
        store.put(id, new ParsedModel(id, file.getOriginalFilename(), parsed, bbox));
        return metadata(id, parsed, file.getOriginalFilename());
    }

    public TreeNode tree(String id) {
        ParsedModel m = require(id);
        BoundingBox b = m.boundingBox();
        BoundingBoxDto dto = new BoundingBoxDto(
                new Vec3(b.minX(), b.minY(), b.minZ()),
                new Vec3(b.maxX(), b.maxY(), b.maxZ()),
                new Vec3(b.centerX(), b.centerY(), b.centerZ()),
                new Vec3(b.sizeX(), b.sizeY(), b.sizeZ()));
        // Slice 1: a single PART node, no children.
        String partName = extractPartName(m.parsed());
        return new TreeNode(id, partName, partName, NodeType.PART, null, dto, List.of());
    }

    public ModelMetadata metadata(String id) {
        ParsedModel m = require(id);
        return metadata(id, m.parsed(), m.fileName());
    }

    public void delete(String id) {
        store.remove(id);
    }

    private ModelMetadata metadata(String id, StepParser.ParsedStepFile parsed, String fileName) {
        long parts = parsed.entities().values().stream()
                .filter(e -> e.type().equals("PRODUCT_DEFINITION")).count();
        return new ModelMetadata(
                id, fileName, parsed.sourceCadSystem(), parsed.schemas(), parsed.unit(),
                Instant.now().toString(), (int) Math.max(1, parts), 0);
    }

    /** Slice 1: pull a display name from the first PRODUCT record, if any. */
    private String extractPartName(StepParser.ParsedStepFile parsed) {
        for (var e : parsed.entities().values()) {
            if (e.type().equals("PRODUCT")) {
                String name = e.stringAt(0);
                if (name != null && !name.isEmpty()) return name;
            }
        }
        return "part";
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
    }

    /** Thrown when a STEP file can't be parsed. Mapped to 422. */
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
