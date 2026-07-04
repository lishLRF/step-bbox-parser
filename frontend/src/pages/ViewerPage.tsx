/**
 * Top-level page: uploader (left), 3D viewer (center), assembly tree (right).
 * Implementation pending — this is a placeholder so the app boots.
 */
export function ViewerPage() {
  return (
    <div className="viewer-layout">
      <aside className="panel panel--left">
        <h2>Upload</h2>
        {/* <ModelUploader /> */}
      </aside>
      <main className="panel panel--center">
        <h2>3D Viewer</h2>
        {/* <BBoxViewer /> */}
      </main>
      <aside className="panel panel--right">
        <h2>Assembly Tree</h2>
        {/* <TreeView /> */}
      </aside>
    </div>
  );
}
