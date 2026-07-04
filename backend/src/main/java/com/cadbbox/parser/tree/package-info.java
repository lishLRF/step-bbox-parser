/**
 * Assembly tree construction.
 *
 * <p>Walks the parsed STEP entities and builds a hierarchical tree of
 * {@code PRODUCT_DEFINITION} nodes linked through
 * {@code NEXT_ASSEMBLY_USAGE_OCCURRENCE}, accumulating the instance transform
 * (axis placement + translation) at each level so that geometry can later be
 * lifted into a single assembly-root coordinate frame.
 */
package com.cadbbox.parser.tree;
