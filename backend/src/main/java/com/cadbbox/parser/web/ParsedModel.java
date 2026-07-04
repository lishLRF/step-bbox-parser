package com.cadbbox.parser.web;

import com.cadbbox.parser.bbox.BoundingBox;
import com.cadbbox.parser.step.StepParser.ParsedStepFile;

/**
 * In-memory cached result of parsing one uploaded model.
 *
 * <p>Slice 1 keeps a single leaf's parse result + its AABB. Slice 2 will widen
 * this to the full tree; the storage contract stays the same.
 */
public record ParsedModel(
        String id,
        String fileName,
        ParsedStepFile parsed,
        BoundingBox boundingBox
) {}
