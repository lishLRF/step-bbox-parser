import { Canvas, useThree } from '@react-three/fiber';
import { OrbitControls, GizmoHelper, GizmoViewport, Grid } from '@react-three/drei';
import * as THREE from 'three';
import { useEffect, useRef } from 'react';
import { useViewerStore } from '../store/viewerStore';
import type { BoundingBox, TreeNode } from '../types/model';

/** 计算整棵树的包围球，用于自动缩放相机 */
function computeOverallBounds(tree: TreeNode): { center: [number,number,number]; radius: number } | null {
  let mnX=Infinity,mnY=Infinity,mnZ=Infinity,mxX=-Infinity,mxY=-Infinity,mxZ=-Infinity;
  let found=false;
  const walk=(n:TreeNode)=>{
    if(n.boundingBox){
      found=true;
      mnX=Math.min(mnX,n.boundingBox.min.x);mxX=Math.max(mxX,n.boundingBox.max.x);
      mnY=Math.min(mnY,n.boundingBox.min.y);mxY=Math.max(mxY,n.boundingBox.max.y);
      mnZ=Math.min(mnZ,n.boundingBox.min.z);mxZ=Math.max(mxZ,n.boundingBox.max.z);
    }
    for(const c of n.children) walk(c);
  };
  walk(tree);
  if(!found) return null;
  const cx=(mnX+mxX)/2, cy=(mnY+mxY)/2, cz=(mnZ+mxZ)/2;
  // 包围球半径 = 对角线的一半
  const dx=mxX-mnX, dy=mxY-mnY, dz=mxZ-mnZ;
  const radius = Math.sqrt(dx*dx+dy*dy+dz*dz)/2;
  return { center:[cx,cy,cz], radius: Math.max(radius, 0.1) };
}

/** 自动缩放相机到模型范围 */
function CameraAutoFit({ tree, controlsRef }: { tree: TreeNode | null; controlsRef: React.RefObject<any> }) {
  const { camera } = useThree();
  useEffect(() => {
    if (!tree) return;
    const bounds = computeOverallBounds(tree);
    if (!bounds) return;
    const [cx, cy, cz] = bounds.center;
    const r = bounds.radius;
    // 相机放在包围球对角线方向，距离 = 半径 * 2.5
    const dist = r * 2.8;
    camera.position.set(cx + dist*0.7, cy + dist*0.5, cz + dist*0.7);
    camera.lookAt(cx, cy, cz);
    camera.near = r * 0.001;   // 避免近距离裁剪
    camera.far = r * 100;      // 避免远距离裁剪
    camera.updateProjectionMatrix();
    if (controlsRef.current) {
      controlsRef.current.target.set(cx, cy, cz);
      controlsRef.current.update();
    }
  }, [tree, camera]);
  return null;
}

/** 右栏：包围盒骨架，分组着色，可切换线框/实心 */
export function BBoxViewer() {
  const { tree, selectedId, multiSelected, displayMode, bboxStyle } = useViewerStore();
  const controlsRef = useRef<any>(null);
  if (!tree) return <div className="viewer viewer--empty">上传模型后显示包围盒骨架。</div>;

  // Build the set of "highlighted" node ids: expand selectedId and every
  // multiSelected id to include ALL descendant PART nodes. Clicking a parent
  // assembly thus lights up every part box under it.
  const highlightedIds = new Set<string>();
  const collectDescendantParts = (node: TreeNode) => {
    if (node.type === 'PART' || node.id.startsWith('merge-')) {
      highlightedIds.add(node.id);
    }
    for (const c of node.children) collectDescendantParts(c);
  };
  const findAndExpand = (root: TreeNode, targetId: string): boolean => {
    if (root.id === targetId) { collectDescendantParts(root); return true; }
    for (const c of root.children) { if (findAndExpand(c, targetId)) return true; }
    return false;
  };
  // Expand the primary selection.
  if (selectedId) findAndExpand(tree, selectedId);
  // Expand each multi-selected node.
  for (const mid of multiSelected) findAndExpand(tree, mid);
  const hasSelection = highlightedIds.size > 0;

  const colorByParent = new Map<string, string>();
  const palette = makePalette();
  const boxes: { id: string; box: BoundingBox; color: string; isMerge: boolean }[] = [];
  let paletteIdx = 0;
  const collect = (node: TreeNode, parentKey: string, depth: number) => {
    const isMerge = node.id.startsWith('merge-');
    let groupKey = parentKey;
    const shouldRender = node.type === 'PART' || isMerge;
    if (shouldRender && node.boundingBox) {
      if (displayMode === 'subtree' && selectedId && !isInSubtree(node, selectedId)) {
        // skip — outside selected subtree
      } else {
        if (!colorByParent.has(groupKey)) {
          colorByParent.set(groupKey, palette[paletteIdx % palette.length]);
          paletteIdx++;
        }
        boxes.push({ id: node.id, box: node.boundingBox, color: colorByParent.get(groupKey)!, isMerge });
      }
    }
    for (const c of node.children) collect(c, node.id, depth + 1);
  };
  collect(tree, '__root__', 0);

  return (
    <div className="viewer">
      <Canvas camera={{ position: [3, 2, 3], fov: 50, near: 0.01, far: 100000 }} dpr={[1, 2]}>
        <ambientLight intensity={0.7} />
        <directionalLight position={[5, 5, 5]} intensity={0.7} />
        <directionalLight position={[-5, 3, -5]} intensity={0.3} />
        <Grid args={[100, 100]} cellSize={0.1} sectionSize={0.5} fadeDistance={200} infiniteGrid />
        <CameraAutoFit tree={tree} controlsRef={controlsRef} />
        {boxes.map((b) => (
          <Cuboid
            key={b.id}
            box={b.box}
            color={b.isMerge ? '#ff6b6b' : b.color}
            solid={bboxStyle === 'solid'}
            dim={hasSelection && !highlightedIds.has(b.id)}
            highlighted={highlightedIds.has(b.id)}
          />
        ))}
        <GizmoHelper alignment="bottom-right" margin={[70, 70]}>
          <GizmoViewport labelColor="white" axisHeadScale={1} />
        </GizmoHelper>
        <OrbitControls ref={controlsRef} makeDefault />
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
          <meshStandardMaterial color={color} transparent opacity={fillOpacity} roughness={0.6} metalness={0.1} />
        </mesh>
      )}
    </group>
  );
}

function isInSubtree(node: TreeNode, target: string): boolean {
  if (node.id === target) return true;
  return node.children.some((c) => isInSubtree(c, target));
}

function makePalette(): string[] {
  const hues = [];
  for (let i = 0; i < 24; i++) {
    const h = Math.round((i * 137.5) % 360);
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
