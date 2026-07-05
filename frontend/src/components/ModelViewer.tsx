import { useEffect, useState } from 'react';
import { Canvas } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid, useGLTF } from '@react-three/drei';
import * as THREE from 'three';
import { useViewerStore } from '../store/viewerStore';
import { api } from '../services/api';
import type { BoundingBox, TreeNode } from '../types/model';

/**
 * Center column: the whole-machine model rendered from real geometry. On mount
 * (and whenever the model id changes) we ask the backend for a tessellated GLB;
 * the first call is slow (OCCT tessellation of the full assembly). While it
 * generates we show a spinner overlay and a faint wireframe of the bboxes as a
 * spatial reference. Once the GLB arrives we render it with drei's useGLTF.
 */
export function ModelViewer() {
  const { tree, metadata } = useViewerStore();
  const [glbUrl, setGlbUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    if (!metadata) { setGlbUrl(null); return; }
    setLoading(true); setError(null); setGlbUrl(null);
    api.getMeshUrl(metadata.id)
      .then((u) => { if (cancelled) { URL.revokeObjectURL(u); return; } url = u; setGlbUrl(u); setLoading(false); })
      .catch((e) => { if (cancelled) return; setError(e?.response?.data?.detail ?? String(e)); setLoading(false); });
    return () => { cancelled = true; if (url) URL.revokeObjectURL(url); };
  }, [metadata?.id]);

  const boxes: { id: string; box: BoundingBox }[] = [];
  if (tree) {
    const collect = (n: TreeNode) => {
      if (n.boundingBox) boxes.push({ id: n.id, box: n.boundingBox });
      for (const c of n.children) collect(c);
    };
    collect(tree);
  }

  return (
    <div className="viewer">
      <Canvas camera={{ position: [3, 2, 3], fov: 50 }} dpr={[1, 2]}>
        <ambientLight intensity={0.8} />
        <directionalLight position={[5, 5, 5]} intensity={0.7} />
        <directionalLight position={[-5, 3, -5]} intensity={0.3} />
        <Grid args={[20, 20]} cellSize={0.1} sectionSize={0.5} fadeDistance={15} infiniteGrid />
        {glbUrl && <GltfModel url={glbUrl} />}
        {/* While the mesh is generating, show faint bbox wireframes as a reference. */}
        {!glbUrl && boxes.map((b) => <WireCuboid key={b.id} box={b.box} />)}
        <GizmoHelper alignment="bottom-right" margin={[70, 70]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls makeDefault />
      </Canvas>
      <div className="viewer__overlay">
        {loading && (
          <div className="viewer__banner">
            <div className="spinner" />
            正在生成整机几何网格（首次较慢，OCCT 曲面细分中…）
          </div>
        )}
        {error && <div className="viewer__banner viewer__banner--err">网格生成失败：{error}</div>}
        {!loading && !error && glbUrl && <div className="viewer__banner viewer__banner--ok">整机几何模型</div>}
      </div>
    </div>
  );
}

function GltfModel({ url }: { url: string }) {
  const { scene } = useGLTF(url);
  // Clone so per-instance material tweaks don't mutate the cached GLTF.
  const cloned = scene.clone(true);
  // Apply a neutral metallic material if the GLB has none.
  cloned.traverse((obj) => {
    if ((obj as THREE.Mesh).isMesh) {
      const m = obj as THREE.Mesh;
      if (!m.material || (Array.isArray(m.material) && m.material.length === 0)) {
        m.material = new THREE.MeshStandardMaterial({ color: '#b0b8c4', metalness: 0.4, roughness: 0.5 });
      }
    }
  });
  return <primitive object={cloned} />;
}

function WireCuboid({ box }: { box: BoundingBox }) {
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
        <lineBasicMaterial color="#5a626d" transparent opacity={0.25} />
      </lineSegments>
    </group>
  );
}
