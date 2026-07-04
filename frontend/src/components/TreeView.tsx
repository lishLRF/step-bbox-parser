import { useState } from 'react';
import { useViewerStore } from '../store/viewerStore';
import type { TreeNode } from '../types/model';

/** Recursive assembly-tree view with multi-select, expand/collapse, search. */
export function TreeView() {
  const { tree, search, loading } = useViewerStore();
  if (loading) return <div className="tree">Loading tree…</div>;
  if (!tree) return <div className="tree tree--empty">No model loaded.</div>;
  return (
    <div className="tree">
      <input
        className="tree__search"
        placeholder="搜索 名称 / 编号…"
        value={search}
        onChange={(e) => useViewerStore.getState().setSearch(e.target.value)}
      />
      <NodeView node={tree} depth={0} />
    </div>
  );
}

function NodeView({ node, depth }: { node: TreeNode; depth: number }) {
  const { selectedId, multiSelected, expanded, search, select, toggleMulti, toggleExpand } = useViewerStore();
  const matches =
    !search ||
    node.name.toLowerCase().includes(search.toLowerCase()) ||
    (node.productLabel ?? '').toLowerCase().includes(search.toLowerCase());
  const dim = search && !matches && node.children.length === 0;
  const isSel = selectedId === node.id;
  const inMulti = multiSelected.has(node.id);
  const isExpanded = expanded.has(node.id) || (!!search && matches);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(node.name);

  if (dim) return null;

  return (
    <div>
      <div
        className={`tree__row ${isSel ? 'tree__row--selected' : ''} ${inMulti ? 'tree__row--multi' : ''}`}
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={(e) => {
          if (e.ctrlKey || e.metaKey) toggleMulti(node.id);
          else select(node.id);
        }}
        onDoubleClick={() => { setDraft(node.name); setEditing(true); }}
      >
        <span className="tree__toggle" onClick={(e) => { e.stopPropagation(); toggleExpand(node.id); }}>
          {node.children.length > 0 ? (isExpanded ? '▼' : '▶') : ' '}
        </span>
        <span className="tree__type">{typeGlyph(node.type)}</span>
        {editing ? (
          <input
            autoFocus
            className="tree__rename"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onClick={(e) => e.stopPropagation()}
            onBlur={() => { setEditing(false); if (draft && draft !== node.name) useViewerStore.getState().renameNode(node.id, draft); }}
            onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
          />
        ) : (
          <span className="tree__name">{node.name}</span>
        )}
        {node.productLabel && node.productLabel !== node.name && (
          <span className="tree__label">{node.productLabel}</span>
        )}
        {node.boundingBox?.size && (
          <span className="tree__size">
            {fmt(node.boundingBox.size.x)}×{fmt(node.boundingBox.size.y)}×{fmt(node.boundingBox.size.z)}
          </span>
        )}
        {node.id.startsWith('merge-') && (
          <span className="tree__del" onClick={(e) => { e.stopPropagation(); useViewerStore.getState().deleteMergeGroup(node.id); }}>✕</span>
        )}
      </div>
      {isExpanded &&
        node.children.map((c) => <NodeView key={c.id} node={c} depth={depth + 1} />)}
    </div>
  );
}

function typeGlyph(t: TreeNode['type']): string {
  return t === 'ASSEMBLY' ? '📦' : t === 'SUBASSEMBLY' ? '🗂' : '🔩';
}
function fmt(n: number): string {
  return Math.abs(n) >= 1 ? n.toFixed(0) : (n * 1000).toFixed(0);
}
