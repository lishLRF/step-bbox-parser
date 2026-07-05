package com.cadbbox.parser.web;

import com.cadbbox.parser.bbox.BoundingBox;
import com.cadbbox.parser.step.StepParser;
import com.cadbbox.parser.tree.AssemblyNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * In-memory cached result of parsing one uploaded model: the parsed STEP file
 * (for downstream queries), the built assembly forest, the per-product
 * local-frame AABB index (built once at upload, reused for every tree query),
 * and the on-disk path to the saved STEP file (used by the mesh generator).
 */
public record ParsedModel(
        String id,
        String fileName,
        StepParser.ParsedStepFile parsed,
        List<AssemblyNode> roots,
        Map<Integer, BoundingBox> productBboxes,
        Path sourceStep
) {}
