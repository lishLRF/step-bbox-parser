import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * Read the backend's chosen port from its `.port` file (written by PortPublisher
 * on startup). Falls back to 8080 if the file is missing (e.g. backend not started
 * yet) so the dev server still boots — requests will just 502 until the backend is up.
 *
 * The .port file lives under <work-dir>/step-bbox-parser/.port, where work-dir
 * resolves to java.io.tmpdir on the backend host. For a local dev workflow we
 * check both the OS tmpdir and a couple of common locations.
 */
function readBackendPort(): number {
  const candidates = [
    process.env.STEP_BBOX_BACKEND_PORT,
    process.env.STEP_BBOX_WORK_DIR && join(process.env.STEP_BBOX_WORK_DIR, '.port'),
    // Match application.yml: Z:/Project/3Dbox-step/cache/work/.port
    join('Z:', 'Project', '3Dbox-step', 'cache', 'work', '.port'),
    // Legacy C: temp location.
    join(tmpdir(), 'step-bbox-parser', 'work', '.port'),
  ].filter(Boolean) as string[];

  for (const c of candidates) {
    // STEP_BBOX_BACKEND_PORT can be a bare port number
    if (/^\d+$/.test(c)) return Number(c);
    try {
      const txt = readFileSync(c, 'utf8').trim();
      const port = Number(txt.split(/\s+/)[0]);
      if (Number.isInteger(port) && port > 0) return port;
    } catch { /* file not present, try next */ }
  }
  return 0; // 0 = let the proxy target fail loudly until the backend advertises.
}

const backendPort = readBackendPort();
console.log(`[vite] proxying /api → http://localhost:${backendPort}`);

export default defineConfig({
  plugins: [react()],
  server: {
    // 0 = let Vite/Node pick a free port. strictPort:false lets it walk up if needed.
    port: 0,
    strictPort: false,
    proxy: {
      '/api': { target: `http://localhost:${backendPort}`, changeOrigin: true },
    },
  },
});
