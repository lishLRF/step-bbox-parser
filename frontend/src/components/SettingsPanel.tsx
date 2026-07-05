import { useEffect, useState } from 'react';

/** 设置面板：缓存目录、Python 路径（可查看可修改可保存） */
export function SettingsPanel({ onClose }: { onClose: () => void }) {
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    fetch('/api/settings')
      .then(r => r.json())
      .then(d => setSettings(d))
      .catch(() => {});
  }, []);

  const save = async () => {
    setSaving(true);
    try {
      await fetch('/api/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch {}
    setSaving(false);
  };

  return (
    <div className="settings-overlay" onClick={onClose}>
      <div className="settings-panel" onClick={(e) => e.stopPropagation()}>
        <div className="settings__header">
          <h3>⚙ 设置</h3>
          <button className="btn btn--sm" onClick={onClose}>✕</button>
        </div>
        <div className="settings__body">
          <label className="settings__label">
            缓存目录
            <input
              className="settings__input"
              type="text"
              value={settings.cacheDir || ''}
              onChange={(e) => setSettings({ ...settings, cacheDir: e.target.value })}
              placeholder="例：Z:/Project/step-bbox-parser/cache"
            />
            <span className="settings__hint">存放上传的模型、解析结果、网格缓存。修改后重启生效。</span>
          </label>
          <label className="settings__label">
            Python 路径（conda 环境）
            <input
              className="settings__input"
              type="text"
              value={settings.pythonExe || ''}
              onChange={(e) => setSettings({ ...settings, pythonExe: e.target.value })}
              placeholder="留空则自动检测"
            />
            <span className="settings__hint">STEP 几何解析用的 conda 环境路径。</span>
          </label>
        </div>
        <div className="settings__footer">
          {saved && <span className="settings__saved">✅ 已保存</span>}
          <button className="btn btn--primary" onClick={save} disabled={saving}>
            {saving ? '保存中…' : '保存设置'}
          </button>
        </div>
      </div>
    </div>
  );
}
