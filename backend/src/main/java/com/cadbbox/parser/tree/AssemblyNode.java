package com.cadbbox.parser.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal assembly-tree node, before serialization to the REST {@code TreeNode}.
 *
 * @param productId        STEP PRODUCT id this node instantiates (parts & assemblies share one)
 * @param name             decoded display name (filled in Slice 3)
 * @param productLabel     part number / product name (filled in Slice 3)
 * @param isAssembly       true if this PRODUCT has children via NAUO
 * @param localTransform   transform of this instance relative to its parent (may be IDENTITY)
 * @param rootTransform    accumulated transform from root to this node (filled by builder)
 * @param children         child instances
 */
public record AssemblyNode(
        int productId,
        String name,
        String productLabel,
        boolean isAssembly,
        Transform4 localTransform,
        Transform4 rootTransform,
        List<AssemblyNode> children
) {
    public AssemblyNode(int productId, String name, String productLabel, boolean isAssembly,
                        Transform4 localTransform) {
        this(productId, name, productLabel, isAssembly, localTransform, localTransform, new ArrayList<>());
    }

    public AssemblyNode withRoot(Transform4 root) {
        return new AssemblyNode(productId, name, productLabel, isAssembly, localTransform, root, children);
    }
}
