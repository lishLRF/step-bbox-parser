import { useRef, useState } from 'react';
import { useViewerStore } from '../store/viewerStore';

/** Drag-and-drop / file-picker uploader for STEP files. */
export function ModelUploader() {
  const { uploading, uploadProgress, error, upload } = useViewerStore();
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFile = (file: File | undefined) => {
    if (!file) return;
    if (!/\.(stp|step)$/i.test(file.name)) {
      alert('Only .stp / .step files are accepted');
      return;
    }
    void upload(file);
  };

  return (
    <div className="uploader">
      <div
        className={`dropzone ${dragOver ? 'dropzone--over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          handleFile(e.dataTransfer.files[0]);
        }}
        onClick={() => inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          accept=".stp,.step"
          hidden
          onChange={(e) => handleFile(e.target.files?.[0])}
        />
        {uploading ? (
          <div className="uploader__progress">
            <div>Uploading… {uploadProgress}%</div>
            <div className="progressbar">
              <div className="progressbar__fill" style={{ width: `${uploadProgress}%` }} />
            </div>
          </div>
        ) : (
          <div className="uploader__hint">
            <strong>Drop a .stp / .step file here</strong>
            <span>or click to choose</span>
          </div>
        )}
      </div>
      {error && <div className="uploader__error">⚠ {error}</div>}
    </div>
  );
}
