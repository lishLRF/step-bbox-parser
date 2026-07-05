import { Canvas } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid } from '@react-three/drei';
import * as THREE from 'three';
import { useViewerStore } from '../store/viewerStore';
import type { BoundingBox, TreeNode } from '../types/model';

/**
 * Center column: the "whole-machine model" view. The intent is to show the
 * model's REAL geometry (B-rep tessellated to triangles). Generating that mesh
 * from STEP requires an external tessellator (FreeCAD/OCCT headless → glTF);
 * the backend endpoint POST /api/models/{id}/mesh is planned but not yet wired.
 *
 * Until the mesh is available, this view shows a neutral-grey wireframe of the
 * bounding boxes as a spatial reference, with a banner explaining the state.
 * When the mesh endpoint returns a glTF URL, swap the placeholder for a
 * <GLTFModel url={...}/> (already supported by @react-three/drei).
 */
export function ModelViewer() {
  const { tree, selectedId } = useViewerStore();
  if (!tree) return <div className="viewer viewer--empty">Upload a model.</div>;

  const boxes: { id: string; box: BoundingBox }[] = [];
  const collect = (n: TreeNode) => {
    if (n.boundingBox) boxes.push({ id: n.id, box: n.boundingBox });
    for (const c of n.children) collect(c);
  };
  collect(tree);

  return (
    <div className="viewer">
      <div className="viewer__banner">
        整机几何模型 · 真实外形渲染需后端网格生成（待接入）
        <span className="viewer__banner-sub">当前显示包围盒线框作为空间参考</span>
      </div>
      <Canvas camera={{ position: [3, 2, 3], fov: 50 }} dpr={[1, 2]}>
        <ambientLight intensity={0.8} />
        <directionalLight position={[5, 5, 5]} intensity={0.6} />
        <Grid args={[20, 20]} cellSize={0.1} sectionSize={0.5} fadeDistance={15} infiniteGrid />
        {boxes.map((b) => (
          <WireCuboid key={b.id} box={b.box} dim={!!selectedId && selectedId !== b.id} />
        ))}
        <GizmoHelper alignment="bottom-right" margin={[70, 70]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls makeDefault />
      </Canvas>
    </div>
  );
}

function WireCuboid({ box, dim }: { box: BoundingBox; dim: boolean }) {
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
        <lineBasicMaterial color="#8a8f99" transparent opacity={dim ? 0.08 : 0.5} />
      </lineSegments>
    </group>
  );
}
