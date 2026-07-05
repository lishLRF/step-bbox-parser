import { ModelUploader } from '../components/ModelUploader';
import { ModelViewer } from '../components/ModelViewer';
import { BBoxViewer } from '../components/BBoxViewer';
import { TreeView } from '../components/TreeView';
import { NodeInspector } from '../components/NodeInspector';
import { useViewerStore, type DisplayMode, type BBoxStyle } from '../store/viewerStore';

/**
 * Three-column workspace:
 *   left   — assembly tree + uploader + tools
 *   center — whole-machine model (real geometry, when available)
 *   right  — bounding-box skeleton (grouped-colored, toggle wireframe/solid)
 */
export function ViewerPage() {
  const {
    metadata, multiSelected, displayMode, bboxStyle,
    mergeSelected, exportStep, setDisplayMode, setBboxStyle,
  } = useViewerStore();

  return (
    <div className="viewer-layout viewer-layout--three">
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
            <div className="toolbar__label">包围盒显示</div>
            {(['solid', 'wireframe'] as BBoxStyle[]).map((s) => (
              <button
                key={s}
                className={`btn ${bboxStyle === s ? 'btn--active' : ''}`}
                onClick={() => setBboxStyle(s)}
              >
                {s === 'solid' ? '实心骨架' : '线框'}
              </button>
            ))}
            <div className="toolbar__label">显示范围</div>
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

      <section className="panel panel--center">
        <div className="panel__caption">整机模型</div>
        <ModelViewer />
      </section>

      <section className="panel panel--center panel--bbox">
        <div className="panel__caption">包围盒骨架</div>
        <BBoxViewer />
        {multiSelected.size >= 2 && (
          <button className="fab" onClick={() => mergeSelected()}>
            合并 {multiSelected.size} 个
          </button>
        )}
      </section>

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
