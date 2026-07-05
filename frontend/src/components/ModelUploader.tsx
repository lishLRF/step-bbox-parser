import { useRef, useState } from 'react';
import { useViewerStore } from '../store/viewerStore';

/** 拖拽 / 点击上传 STEP 文件，支持自定义命名 */
export function ModelUploader() {
  const { uploading, uploadProgress, bboxProgress, error, upload } = useViewerStore();
  const [dragOver, setDragOver] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [modelName, setModelName] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const bboxPct = (() => {
    if (!bboxProgress || bboxProgress === 'done') return null;
    const [done, total] = bboxProgress.split('/');
    if (total && total !== '?') return Math.round((parseInt(done) / parseInt(total)) * 100);
    return null;
  })();

  const handleFile = (file: File | undefined) => {
    if (!file) return;
    if (!/\.(stp|step)$/i.test(file.name)) { alert('仅支持 .stp / .step 文件'); return; }
    setSelectedFile(file);
    setModelName(file.name.replace(/\.(stp|step)$/i, ''));
  };

  const doUpload = () => {
    if (!selectedFile) return;
    void upload(selectedFile, modelName || selectedFile.name);
    setSelectedFile(null);
    setModelName('');
  };

  const isBusy = uploading || (bboxProgress != null && bboxProgress !== 'done');

  return (
    <div className="uploader">
      <div
        className={`dropzone ${dragOver ? 'dropzone--over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => { e.preventDefault(); setDragOver(false); handleFile(e.dataTransfer.files[0]); }}
        onClick={() => !selectedFile && !isBusy && inputRef.current?.click()}
      >
        <input ref={inputRef} type="file" accept=".stp,.step" hidden
          onChange={(e) => handleFile(e.target.files?.[0])} />
        {isBusy ? (
          <div className="uploader__progress">
            <div>上传中… {uploadProgress}%</div>
            <div className="progressbar"><div className="progressbar__fill" style={{ width: `${uploadProgress}%` }} /></div>
            {bboxProgress && bboxProgress !== 'done' && (
              <>
                <div style={{ marginTop: 8 }}>几何解析中… {bboxProgress}</div>
                {bboxPct !== null && <div className="progressbar"><div className="progressbar__fill" style={{ width: `${bboxPct}%` }} /></div>}
              </>
            )}
            {bboxProgress === 'done' && <div style={{ marginTop: 8 }}>✅ 解析完成，加载中…</div>}
          </div>
        ) : selectedFile ? (
          <div className="uploader__confirm" onClick={(e) => e.stopPropagation()}>
            <div className="uploader__filename">📎 {selectedFile.name}</div>
            <input
              className="uploader__name-input"
              type="text"
              placeholder="给模型命名…"
              value={modelName}
              onChange={(e) => setModelName(e.target.value)}
            />
            <div className="uploader__buttons">
              <button className="btn btn--primary" onClick={doUpload}>开始解析</button>
              <button className="btn" onClick={() => { setSelectedFile(null); }}>取消</button>
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
