import { useEffect, useState, useRef } from 'react';
import { Canvas, useThree } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid, useGLTF } from '@react-three/drei';
import * as THREE from 'three';
import { useViewerStore } from '../store/viewerStore';
import { api } from '../services/api';
import type { BoundingBox, TreeNode } from '../types/model';

/** 自动缩放相机到模型范围 */
function CameraAutoFit({ tree, controlsRef }: { tree: TreeNode | null; controlsRef: React.RefObject<any> }) {
  const { camera } = useThree();
  useEffect(() => {
    if (!tree) return;
    let mnX=Infinity,mnY=Infinity,mnZ=Infinity,mxX=-Infinity,mxY=-Infinity,mxZ=-Infinity;
    let found=false;
    const walk=(n:TreeNode)=>{
      if(n.boundingBox){found=true;mnX=Math.min(mnX,n.boundingBox.min.x);mxX=Math.max(mxX,n.boundingBox.max.x);mnY=Math.min(mnY,n.boundingBox.min.y);mxY=Math.max(mxY,n.boundingBox.max.y);mnZ=Math.min(mnZ,n.boundingBox.min.z);mxZ=Math.max(mxZ,n.boundingBox.max.z);}
      for(const c of n.children) walk(c);
    };
    walk(tree);
    if(!found) return;
    const cx=(mnX+mxX)/2,cy=(mnY+mxY)/2,cz=(mnZ+mxZ)/2;
    const dx=mxX-mnX,dy=mxY-mnY,dz=mxZ-mnZ;
    const r=Math.max(Math.sqrt(dx*dx+dy*dy+dz*dz)/2, 0.1);
    const dist=r*2.8;
    camera.position.set(cx+dist*0.7, cy+dist*0.5, cz+dist*0.7);
    camera.lookAt(cx,cy,cz);
    camera.near=r*0.001; camera.far=r*100; camera.updateProjectionMatrix();
    if(controlsRef.current){controlsRef.current.target.set(cx,cy,cz);controlsRef.current.update();}
  }, [tree, camera]);
  return null;
}

/** 中栏：整机真实几何模型 */
export function ModelViewer() {
  const { tree, metadata } = useViewerStore();
  const [glbUrl, setGlbUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controlsRef = useRef<any>(null);

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    if (!metadata) { setGlbUrl(null); return; }
    setLoading(true); setError(null); setGlbUrl(null);
    api.getMeshUrl(metadata.id)
      .then((u) => { if (cancelled) { URL.revokeObjectURL(u); return; } url = u; setGlbUrl(u); setLoading(false); })
      .catch((e: any) => { if (cancelled) return; setError(e?.response?.data?.detail ?? String(e)); setLoading(false); });
    return () => { cancelled = true; if (url) URL.revokeObjectURL(url); };
  }, [metadata?.id]);

  const boxes: { id: string; box: BoundingBox }[] = [];
  if (tree) {
    const collect = (n: TreeNode) => {
      // Only show PART-level wireframes (skip assembly overall bbox).
      if (n.boundingBox && n.type === 'PART') boxes.push({ id: n.id, box: n.boundingBox });
      for (const c of n.children) collect(c);
    };
    collect(tree);
  }

  return (
    <div className="viewer">
      <Canvas camera={{ position: [3, 2, 3], fov: 50, near: 0.01, far: 100000 }} dpr={[1, 2]}>
        <ambientLight intensity={0.8} />
        <directionalLight position={[5, 5, 5]} intensity={0.7} />
        <directionalLight position={[-5, 3, -5]} intensity={0.3} />
        <Grid args={[100, 100]} cellSize={0.1} sectionSize={0.5} fadeDistance={200} infiniteGrid />
        <CameraAutoFit tree={tree} controlsRef={controlsRef} />
        {glbUrl && <GltfModel url={glbUrl} />}
        {!glbUrl && boxes.map((b) => <WireCuboid key={b.id} box={b.box} />)}
        <GizmoHelper alignment="bottom-right" margin={[70, 70]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls ref={controlsRef} makeDefault />
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
  const cloned = scene.clone(true);
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
