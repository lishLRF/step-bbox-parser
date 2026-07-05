import { useEffect, useState } from 'react';
import { useViewerStore } from '../store/viewerStore';
import { api } from '../services/api';

interface CachedModel {
  id: string;
  name: string;
  parts: number;
  parsedAt: string;
  hasMesh: boolean;
  stpSize: number;
}

/** 已缓存的模型列表（秒级加载，支持重命名） */
export function CachedModels() {
  const [models, setModels] = useState<CachedModel[]>([]);
  const [, setLoading] = useState(false);
  const { loadCachedModel } = useViewerStore();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');

  const refresh = async () => {
    setLoading(true);
    try {
      const resp = await fetch('/api/models/cached');
      const data = await resp.json();
      setModels(data);
    } catch { /* ignore */ }
    setLoading(false);
  };

  useEffect(() => { refresh(); }, []);

  const doRename = async (id: string) => {
    if (editName.trim()) {
      await api.renameModel(id, editName.trim());
      setModels(models.map(m => m.id === id ? { ...m, name: editName.trim() } : m));
    }
    setEditingId(null);
  };

  if (models.length === 0) return null;

  return (
    <div className="cached-models">
      <div className="cached-models__header">
        历史模型（秒级加载）
        <button className="btn btn--sm" onClick={refresh}>刷新</button>
      </div>
      {models.map((m) => (
        <div key={m.id} className="cached-models__item" onClick={() => !editingId && loadCachedModel(m.id)}>
          {editingId === m.id ? (
            <div onClick={(e) => e.stopPropagation()}>
              <input
                className="uploader__name-input"
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
                autoFocus
                onKeyDown={(e) => { if (e.key === 'Enter') doRename(m.id); if (e.key === 'Escape') setEditingId(null); }}
                onBlur={() => doRename(m.id)}
              />
            </div>
          ) : (
            <>
              <div className="cached-models__name">
                {m.name || '未命名'}
                {m.hasMesh && <span className="cached-models__mesh">✓ 网格</span>}
                <span className="cached-models__rename" onClick={(e) => { e.stopPropagation(); setEditingId(m.id); setEditName(m.name || ''); }}>✏</span>
              </div>
              <div className="cached-models__meta">
                {m.parts > 0 ? `${m.parts} 零件 · ` : ''}{m.parsedAt} · {(m.stpSize / 1024 / 1024).toFixed(1)}MB
              </div>
            </>
          )}
        </div>
      ))}
    </div>
  );
}
