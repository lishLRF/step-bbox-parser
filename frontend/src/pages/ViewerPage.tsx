import { ModelUploader } from '../components/ModelUploader';
import { BBoxViewer } from '../components/BBoxViewer';
import { TreeView } from '../components/TreeView';
import { useViewerStore } from '../store/viewerStore';

/** Top-level page: uploader (left), 3D viewer (center), assembly tree (right). */
export function ViewerPage() {
  const { metadata } = useViewerStore();
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
      </aside>
      <main className="panel panel--center">
        <BBoxViewer />
      </main>
      <aside className="panel panel--right">
        <h2>Assembly Tree</h2>
        <TreeView />
      </aside>
    </div>
  );
}
