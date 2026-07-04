import { useViewerStore } from '../store/viewerStore';
import type { TreeNode } from '../types/model';

/** Right-side detail panel for the currently-selected node. */
export function NodeInspector() {
  const { tree, selectedId } = useViewerStore();
  if (!tree || !selectedId) return <div className="inspector inspector--empty">Select a node.</div>;
  const node = findNode(tree, selectedId);
  if (!node) return null;
  const b = node.boundingBox;
  return (
    <div className="inspector">
      <div className="inspector__name">{node.name}</div>
      {node.productLabel && node.productLabel !== node.name && (
        <div className="inspector__label">{node.productLabel}</div>
      )}
      <div className="inspector__type">{node.type}{node.id.startsWith('merge-') ? ' (合并组)' : ''}</div>
      {b?.size && b?.center && (
        <table className="inspector__table">
          <tbody>
            <tr><td>尺寸 X</td><td>{fmt(b.size.x)}</td></tr>
            <tr><td>尺寸 Y</td><td>{fmt(b.size.y)}</td></tr>
            <tr><td>尺寸 Z</td><td>{fmt(b.size.z)}</td></tr>
            <tr><td>中心 X</td><td>{fmt(b.center.x)}</td></tr>
            <tr><td>中心 Y</td><td>{fmt(b.center.y)}</td></tr>
            <tr><td>中心 Z</td><td>{fmt(b.center.z)}</td></tr>
          </tbody>
        </table>
      )}
      <div className="inspector__hint">双击节点名可重命名</div>
    </div>
  );
}

function findNode(node: TreeNode, id: string): TreeNode | null {
  if (node.id === id) return node;
  for (const c of node.children) {
    const f = findNode(c, id);
    if (f) return f;
  }
  return null;
}

function fmt(n: number): string {
  return Math.abs(n) >= 1 ? n.toFixed(3) : (n * 1000).toFixed(2);
}
