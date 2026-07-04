import { Canvas } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid } from '@react-three/drei';
import * as THREE from 'three';
import { useViewerStore } from '../store/viewerStore';
import type { BoundingBox, TreeNode } from '../types/model';

/**
 * 3D viewer that renders every part's bounding box as a wireframe cuboid in
 * assembly-root coordinates. Slice 1: usually a single cuboid.
 */
export function BBoxViewer() {
  const { tree } = useViewerStore();
  const boxes: { id: string; box: BoundingBox }[] = [];
  if (tree) collectBoxes(tree, boxes);

  if (boxes.length === 0) {
    return <div className="viewer viewer--empty">Upload a model to see its bounding box.</div>;
  }

  return (
    <div className="viewer">
      <Canvas camera={{ position: [1.5, 1.2, 1.5], fov: 50 }} dpr={[1, 2]}>
        <ambientLight intensity={0.6} />
        <directionalLight position={[5, 5, 5]} intensity={0.8} />
        <Grid
          args={[10, 10]}
          cellSize={0.1}
          sectionSize={0.5}
          fadeDistance={10}
          infiniteGrid
        />
        {boxes.map((b) => (
          <Cuboid key={b.id} box={b.box} />
        ))}
        <GizmoHelper alignment="bottom-right" margin={[80, 80]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls makeDefault />
      </Canvas>
    </div>
  );
}

function Cuboid({ box }: { box: BoundingBox }) {
  const sx = box.max.x - box.min.x;
  const sy = box.max.y - box.min.y;
  const sz = box.max.z - box.min.z;
  const cx = (box.min.x + box.max.x) / 2;
  const cy = (box.min.y + box.max.y) / 2;
  const cz = (box.min.z + box.max.z) / 2;
  return (
    <group position={[cx, cy, cz]}>
      {/* wireframe edges around a unit box scaled to the AABB */}
      <lineSegments>
        <edgesGeometry args={[new THREE.BoxGeometry(sx, sy, sz)]} />
        <lineBasicMaterial color="#4aa8ff" />
      </lineSegments>
      {/* translucent fill for depth perception */}
      <mesh>
        <boxGeometry args={[sx, sy, sz]} />
        <meshBasicMaterial color="#4aa8ff" transparent opacity={0.08} />
      </mesh>
    </group>
  );
}

function collectBoxes(node: TreeNode, out: { id: string; box: BoundingBox }[]) {
  if (node.boundingBox) out.push({ id: node.id, box: node.boundingBox });
  for (const c of node.children) collectBoxes(c, out);
}
