package com.cadbbox.parser.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A node in the assembly tree.
 *
 * @param id            stable per-node id (slice 1: the model id for a single leaf)
 * @param name          decoded display name (slice 3 fills this richly)
 * @param productLabel  part number / product name (slice 3)
 * @param type          assembly / sub-assembly / part
 * @param transform     local 4x4 transform relative to parent (slice 2 fills this)
 * @param boundingBox   AABB in assembly-root coordinates (null for pure assemblies)
 * @param children      child nodes (empty for a leaf part)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreeNode(
        String id,
        String name,
        String productLabel,
        NodeType type,
        double[] transform,
        BoundingBoxDto boundingBox,
        List<TreeNode> children
) {}
