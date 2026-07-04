import { Canvas } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid } from '@react-three/drei';
import * as THREE from 'three';
import { useViewerStore } from '../store/viewerStore';
import type { BoundingBox, TreeNode } from '../types/model';

/**
 * 3D viewer: renders every part's bounding box as a wireframe cuboid in
 * assembly-root coordinates. Honors the display mode and selection.
 */
export function BBoxViewer() {
  const { tree, selectedId, multiSelected, displayMode } = useViewerStore();
  if (!tree) return <div className="viewer viewer--empty">Upload a model to see its bounding boxes.</div>;

  const boxes: { id: string; box: BoundingBox; isMerge: boolean }[] = [];
  collectBoxes(tree, boxes, displayMode, selectedId);

  return (
    <div className="viewer">
      <Canvas camera={{ position: [2, 1.6, 2], fov: 50 }} dpr={[1, 2]}>
        <ambientLight intensity={0.6} />
        <directionalLight position={[5, 5, 5]} intensity={0.8} />
        <Grid args={[20, 20]} cellSize={0.1} sectionSize={0.5} fadeDistance={15} infiniteGrid />
        {boxes.map((b) => (
          <Cuboid
            key={b.id}
            box={b.box}
            color={b.isMerge ? '#ff6b6b' : '#4aa8ff'}
            dim={!!selectedId && selectedId !== b.id && !multiSelected.has(b.id)}
            highlighted={selectedId === b.id || multiSelected.has(b.id)}
          />
        ))}
        <GizmoHelper alignment="bottom-right" margin={[80, 80]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls makeDefault />
      </Canvas>
    </div>
  );
}

function Cuboid({ box, color, dim, highlighted }: { box: BoundingBox; color: string; dim: boolean; highlighted: boolean }) {
  const sx = box.max.x - box.min.x;
  const sy = box.max.y - box.min.y;
  const sz = box.max.z - box.min.z;
  const cx = (box.min.x + box.max.x) / 2;
  const cy = (box.min.y + box.max.y) / 2;
  const cz = (box.min.z + box.max.z) / 2;
  return (
    <group position={[cx, cy, cz]}>
      <lineSegments>
        <edgesGeometry args={[new THREE.BoxGeometry(sx, sy, sz)]} />
        <lineBasicMaterial color={highlighted ? '#ffffff' : color} transparent opacity={dim ? 0.12 : 0.9} />
      </lineSegments>
      <mesh>
        <boxGeometry args={[sx, sy, sz]} />
        <meshBasicMaterial color={color} transparent opacity={dim ? 0.02 : highlighted ? 0.18 : 0.06} />
      </mesh>
    </group>
  );
}

function collectBoxes(node: TreeNode, out: { id: string; box: BoundingBox; isMerge: boolean }[],
                      mode: string, selectedId: string | null) {
  const isMerge = node.id.startsWith('merge-');
  if (mode === 'leaf' && (node.type === 'ASSEMBLY' || node.type === 'SUBASSEMBLY') && !isMerge) {
    // skip non-leaf assemblies
  } else if (mode === 'subtree' && selectedId && !isInSubtree(node, selectedId, selectedId)) {
    // skip nodes outside the selected subtree
  } else if (node.boundingBox) {
    out.push({ id: node.id, box: node.boundingBox, isMerge });
  }
  for (const c of node.children) collectBoxes(c, out, mode, selectedId);
}

function isInSubtree(node: TreeNode, rootId: string, target: string): boolean {
  if (node.id === target) return true;
  return node.children.some((c) => isInSubtree(c, rootId, target));
}
