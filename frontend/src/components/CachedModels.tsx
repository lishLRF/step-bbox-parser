import { useEffect, useState } from 'react';
import { useViewerStore } from '../store/viewerStore';

interface CachedModel {
  id: string;
  parts: number;
  parsedAt: string;
  hasMesh: boolean;
  stpSize: number;
}

/** 已缓存的模型列表（秒级加载，跳过重新解析） */
export function CachedModels() {
  const [models, setModels] = useState<CachedModel[]>([]);
  const [, setLoading] = useState(false);
  const { loadCachedModel } = useViewerStore();

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

  if (models.length === 0) return null;

  return (
    <div className="cached-models">
      <div className="cached-models__header">
        历史模型（秒级加载）
        <button className="btn btn--sm" onClick={refresh}>刷新</button>
      </div>
      {models.map((m) => (
        <div
          key={m.id}
          className="cached-models__item"
          onClick={() => loadCachedModel(m.id)}
        >
          <div className="cached-models__name">
            {m.parts > 0 ? `${m.parts} 个零件` : '已缓存'}
            {m.hasMesh && <span className="cached-models__mesh">✓ 网格</span>}
          </div>
          <div className="cached-models__meta">
            {m.parsedAt} · {(m.stpSize / 1024 / 1024).toFixed(1)}MB
          </div>
        </div>
      ))}
    </div>
  );
}
