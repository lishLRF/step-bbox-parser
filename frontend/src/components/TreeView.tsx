import { useViewerStore } from '../store/viewerStore';
import type { TreeNode } from '../types/model';

/** Recursive assembly-tree view. Slice 1: a single node. */
export function TreeView() {
  const { tree, selectedId, select, loading } = useViewerStore();
  if (loading) return <div className="tree">Loading tree…</div>;
  if (!tree) return <div className="tree tree--empty">No model loaded.</div>;
  return (
    <div className="tree">
      <NodeRow node={tree} depth={0} selectedId={selectedId} onSelect={select} />
    </div>
  );
}

function NodeRow({
  node, depth, selectedId, onSelect,
}: {
  node: TreeNode; depth: number; selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  const selected = selectedId === node.id;
  return (
    <div>
      <div
        className={`tree__row ${selected ? 'tree__row--selected' : ''}`}
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={() => onSelect(node.id)}
      >
        <span className="tree__type">{typeGlyph(node.type)}</span>
        <span className="tree__name">{node.name}</span>
        {node.productLabel && node.productLabel !== node.name && (
          <span className="tree__label">{node.productLabel}</span>
        )}
        {node.boundingBox?.size && (
          <span className="tree__size">
            {fmt(node.boundingBox.size.x)} × {fmt(node.boundingBox.size.y)} × {fmt(node.boundingBox.size.z)}
          </span>
        )}
      </div>
      {node.children.map((c) => (
        <NodeRow key={c.id} node={c} depth={depth + 1} selectedId={selectedId} onSelect={onSelect} />
      ))}
    </div>
  );
}

function typeGlyph(t: TreeNode['type']): string {
  return t === 'ASSEMBLY' ? '📦' : t === 'SUBASSEMBLY' ? '🗂' : '🔩';
}
function fmt(n: number): string {
  return Math.abs(n) >= 1 ? n.toFixed(1) : (n * 1000).toFixed(1);
}
