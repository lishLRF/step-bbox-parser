/**
 * STEP (ISO-10303-21) file parsing.
 *
 * <p>Responsible for tokenizing the DATA section and materializing the relevant
 * entities (CARTESIAN_POINT, DIRECTION, AXIS2_PLACEMENT_3D, MANIFOLD_SOLID_BREP,
 * PRODUCT_DEFINITION, NEXT_ASSEMBLY_USAGE_OCCURRENCE, etc.) into in-memory
 * records.
 *
 * <p>No external CAD kernel is required — the parser is a pure text/grammar
 * processor over the STEP exchange format.
 */
package com.cadbbox.parser.step;
