/**
 * Shared TypeScript types — mirror of the backend DTOs in
 * `com.cadbbox.parser.web.dto`. Keep these in sync manually (or generate from
 * the OpenAPI spec once one is published).
 */

export interface Vec3 {
  x: number;
  y: number;
  z: number;
}

/** Axis-aligned bounding box in assembly-root coordinates. */
export interface BoundingBox {
  min: Vec3;
  max: Vec3;
  /** Optional: oriented cuboid for non-axis-aligned parts. */
  center?: Vec3;
  size?: Vec3;
}

export type NodeType = 'ASSEMBLY' | 'SUBASSEMBLY' | 'PART';

export interface TreeNode {
  id: string;
  name: string;
  type: NodeType;
  /** STEP product identifier (e.g. part number). */
  productLabel?: string;
  /** Local transform of this instance inside its parent. */
  transform?: Transform4;
  /** Computed bounding box in assembly-root coordinates (null for assemblies). */
  boundingBox?: BoundingBox | null;
  children: TreeNode[];
}

export interface Transform4 {
  /** Row-major 4x4 matrix. */
  matrix: number[];
}

export interface ModelMetadata {
  id: string;
  fileName: string;
  sourceCadSystem: string;
  schema: string[];
  unit: 'MILLIMETER' | 'CENTIMETER' | 'METER' | 'INCH';
  parsedAt: string;
  partCount: number;
  assemblyCount: number;
}

export interface ParsedModel {
  metadata: ModelMetadata;
  root: TreeNode;
}
