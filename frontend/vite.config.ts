import { defineConfig } from 'vite';
import react from '@vite/js/plugin-react';
import { readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

/**
 * Read the backend's chosen port from its `.port` file.
 * The .port file is at <project-root>/.cache/work/.port (relative, portable).
 * Also checks STEP_BBOX_CACHE_DIR env var and legacy temp locations.
 */
function readBackendPort(): number {
  // Project root = parent of frontend/
  const projRoot = resolve(__dirname, '..');
  const candidates = [
    process.env.STEP_BBOX_BACKEND_PORT,
    process.env.STEP_BBOX_CACHE_DIR && join(process.env.STEP_BBOX_CACHE_DIR, 'work', '.port'),
    join(projRoot, '.cache', 'work', '.port'),
    join(tmpdir(), 'step-bbox-parser', 'work', '.port'), // legacy
  ].filter(Boolean) as string[];

  for (const c of candidates) {
    if (/^\d+$/.test(c)) return Number(c);
    try {
      if (!existsSync(c)) continue;
      const txt = readFileSync(c, 'utf8').trim();
      const port = Number(txt.split(/\s+/)[0]);
      if (Number.isInteger(port) && port > 0) return port;
    } catch { /* try next */ }
  }
  return 0;
}

const backendPort = readBackendPort();
console.log(`[vite] proxying /api → http://localhost:${backendPort}`);

export default defineConfig({
  plugins: [react()],
  server: {
    port: 0,
    strictPort: false,
    proxy: {
      '/api': { target: `http://localhost:${backendPort}`, changeOrigin: true },
    },
  },
});
