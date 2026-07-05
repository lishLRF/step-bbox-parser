package com.cadbbox.parser.web;

import com.cadbbox.parser.bbox.BoundingBox;
import com.cadbbox.parser.step.StepParser;
import com.cadbbox.parser.tree.AssemblyNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * In-memory cached result of parsing one uploaded model.
 *
 * @param productBboxes   per-product AABB from text parsing (fallback)
 * @param occtBboxes      per-part AABB from OCCT (authoritative, may be null if
 *                        the Python converter hasn't run yet). Keyed by
 *                        "_solid_N" / "_shell_N" / "__overall__".
 */
public record ParsedModel(
        String id,
        String fileName,
        StepParser.ParsedStepFile parsed,
        List<AssemblyNode> roots,
        Map<Integer, BoundingBox> productBboxes,
        Path sourceStep,
        Map<String, double[]> occtBboxes
) {}
