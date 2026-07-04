import { ModelUploader } from '../components/ModelUploader';
import { BBoxViewer } from '../components/BBoxViewer';
import { TreeView } from '../components/TreeView';
import { NodeInspector } from '../components/NodeInspector';
import { useViewerStore, type DisplayMode } from '../store/viewerStore';

/** Top-level page: uploader (left), 3D viewer (center), assembly tree + inspector (right). */
export function ViewerPage() {
  const { metadata, multiSelected, displayMode, mergeSelected, exportStep, setDisplayMode } = useViewerStore();
  return (
    <div className="viewer-layout">
      <aside className="panel panel--left">
        <h2>Upload</h2>
        <ModelUploader />
        {metadata && (
          <div className="metadata">
            <div><strong>{metadata.fileName}</strong></div>
            <div>CAD: {metadata.sourceCadSystem}</div>
            <div>Schema: {metadata.schema.join(', ')}</div>
            <div>Unit: {metadata.unit}</div>
            <div>Parts: {metadata.partCount}</div>
          </div>
        )}
        {metadata && (
          <div className="toolbar toolbar--left">
            <div className="toolbar__label">显示模式</div>
            {(['all', 'subtree', 'leaf'] as DisplayMode[]).map((m) => (
              <button
                key={m}
                className={`btn ${displayMode === m ? 'btn--active' : ''}`}
                onClick={() => setDisplayMode(m)}
              >
                {m === 'all' ? '全部' : m === 'subtree' ? '仅选中子树' : '仅叶子'}
              </button>
            ))}
            <div className="toolbar__label">导出</div>
            <button className="btn" onClick={() => exportStep()}>导出 STEP</button>
          </div>
        )}
      </aside>
      <main className="panel panel--center">
        <BBoxViewer />
        {multiSelected.size >= 2 && (
          <button className="fab" onClick={() => mergeSelected()}>
            合并 {multiSelected.size} 个
          </button>
        )}
      </main>
      <aside className="panel panel--right">
        <NodeInspector />
        <h2>Assembly Tree</h2>
        <TreeView />
        {multiSelected.size > 0 && (
          <div className="multiselect-hint">
            已选 {multiSelected.size} 个 · Ctrl/Cmd 点击多选 · 选 ≥2 个后点「合并」
          </div>
        )}
      </aside>
    </div>
  );
}
