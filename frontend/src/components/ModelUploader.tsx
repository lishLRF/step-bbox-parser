import { useRef, useState } from 'react';
import { useViewerStore } from '../store/viewerStore';

/** 拖拽 / 点击上传 STEP 文件 */
export function ModelUploader() {
  const { uploading, uploadProgress, error, upload } = useViewerStore();
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFile = (file: File | undefined) => {
    if (!file) return;
    if (!/\.(stp|step)$/i.test(file.name)) {
      alert('仅支持 .stp / .step 文件');
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
            <div>上传中… {uploadProgress}%</div>
            <div className="progressbar">
              <div className="progressbar__fill" style={{ width: `${uploadProgress}%` }} />
            </div>
          </div>
        ) : (
          <div className="uploader__hint">
            <strong>拖入 .stp / .step 文件</strong>
            <span>或点击选择</span>
          </div>
        )}
      </div>
      {error && <div className="uploader__error">⚠ {error}</div>}
    </div>
  );
}
