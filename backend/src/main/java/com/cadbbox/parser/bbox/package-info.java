/**
 * Bounding-box computation.
 *
 * <p>For each leaf part, accumulates all referenced geometric points (vertex /
 * Cartesian point / control points of B-rep surfaces), applies the chain of
 * instance transforms up to the assembly root, and produces a tight
 * axis-aligned bounding box (AABB) in root coordinates. The AABB is also
 * expressed as a positioned cuboid (center + size + orientation) for rendering.
 */
package com.cadbbox.parser.bbox;
