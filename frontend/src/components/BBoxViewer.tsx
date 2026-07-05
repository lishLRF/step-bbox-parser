import { Canvas } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid } from '@react-three/drei';
import * as THREE from 'three';
import { useViewerStore } from '../store/viewerStore';
import type { BoundingBox, TreeNode } from '../types/model';

/**
 * Right column: bounding-box skeleton. Renders every part's AABB as either a
 * solid (grouped-colored) cuboid or a wireframe, per the user's toggle.
 * Parts sharing the same immediate sub-assembly parent share a hue so the
 * assembly structure is visually readable.
 */
export function BBoxViewer() {
  const { tree, selectedId, multiSelected, displayMode, bboxStyle } = useViewerStore();
  if (!tree) return <div className="viewer viewer--empty">上传模型后显示包围盒骨架。</div>;

  // Assign each node a color keyed by its parent's id (siblings share a hue).
  const colorByParent = new Map<string, string>();
  const palette = makePalette();
  const boxes: { id: string; box: BoundingBox; color: string; isMerge: boolean }[] = [];
  let paletteIdx = 0;
  const collect = (node: TreeNode, parentKey: string) => {
    const isMerge = node.id.startsWith('merge-');
    let groupKey = parentKey;
    if (displayMode === 'leaf' && (node.type === 'ASSEMBLY' || node.type === 'SUBASSEMBLY') && !isMerge) {
      // skip non-leaf assemblies in leaf mode
    } else if (displayMode === 'subtree' && selectedId && !isInSubtree(node, selectedId)) {
      // skip nodes outside selected subtree
    } else if (node.boundingBox) {
      if (!colorByParent.has(groupKey)) {
        colorByParent.set(groupKey, palette[paletteIdx % palette.length]);
        paletteIdx++;
      }
      boxes.push({ id: node.id, box: node.boundingBox, color: colorByParent.get(groupKey)!, isMerge });
    }
    for (const c of node.children) collect(c, node.id);
  };
  collect(tree, '__root__');

  return (
    <div className="viewer">
      <Canvas camera={{ position: [3, 2, 3], fov: 50 }} dpr={[1, 2]}>
        <ambientLight intensity={0.7} />
        <directionalLight position={[5, 5, 5]} intensity={0.7} />
        <directionalLight position={[-5, 3, -5]} intensity={0.3} />
        <Grid args={[20, 20]} cellSize={0.1} sectionSize={0.5} fadeDistance={15} infiniteGrid />
        {boxes.map((b) => (
          <Cuboid
            key={b.id}
            box={b.box}
            color={b.isMerge ? '#ff6b6b' : b.color}
            solid={bboxStyle === 'solid'}
            dim={!!selectedId && selectedId !== b.id && !multiSelected.has(b.id)}
            highlighted={selectedId === b.id || multiSelected.has(b.id)}
          />
        ))}
        <GizmoHelper alignment="bottom-right" margin={[70, 70]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls makeDefault />
      </Canvas>
    </div>
  );
}

function Cuboid({ box, color, solid, dim, highlighted }: {
  box: BoundingBox; color: string; solid: boolean; dim: boolean; highlighted: boolean;
}) {
  const sx = box.max.x - box.min.x;
  const sy = box.max.y - box.min.y;
  const sz = box.max.z - box.min.z;
  const cx = (box.min.x + box.max.x) / 2;
  const cy = (box.min.y + box.max.y) / 2;
  const cz = (box.min.z + box.max.z) / 2;
  const edgeColor = highlighted ? '#ffffff' : color;
  const fillOpacity = dim ? 0.03 : highlighted ? 0.55 : solid ? 0.4 : 0.06;
  return (
    <group position={[cx, cy, cz]}>
      <lineSegments>
        <edgesGeometry args={[new THREE.BoxGeometry(sx, sy, sz)]} />
        <lineBasicMaterial color={edgeColor} transparent opacity={dim ? 0.1 : 0.95} />
      </lineSegments>
      {solid && (
        <mesh>
          <boxGeometry args={[sx, sy, sz]} />
          <meshStandardMaterial
            color={color}
            transparent
            opacity={fillOpacity}
            roughness={0.6}
            metalness={0.1}
          />
        </mesh>
      )}
    </group>
  );
}

function isInSubtree(node: TreeNode, target: string): boolean {
  if (node.id === target) return true;
  return node.children.some((c) => isInSubtree(c, target));
}

/** A spread of distinct, saturated hues for grouping sibling parts. */
function makePalette(): string[] {
  const hues = [];
  for (let i = 0; i < 24; i++) {
    const h = Math.round((i * 137.5) % 360); // golden-angle spread
    hues.push(hslToHex(h, 65, 55));
  }
  return hues;
}

function hslToHex(h: number, s: number, l: number): string {
  s /= 100; l /= 100;
  const k = (n: number) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => {
    const c = l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
    return Math.round(c * 255).toString(16).padStart(2, '0');
  };
  return `#${f(0)}${f(8)}${f(4)}`;
}
