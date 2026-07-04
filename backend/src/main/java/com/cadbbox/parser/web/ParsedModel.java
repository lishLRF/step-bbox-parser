package com.cadbbox.parser.web;

import com.cadbbox.parser.step.StepParser.ParsedStepFile;
import com.cadbbox.parser.tree.AssemblyNode;

import java.util.List;

/**
 * In-memory cached result of parsing one uploaded model: the parsed STEP file
 * (for downstream bbox/export queries) and the built assembly forest.
 */
public record ParsedModel(
        String id,
        String fileName,
        ParsedStepFile parsed,
        List<AssemblyNode> roots
) {}
