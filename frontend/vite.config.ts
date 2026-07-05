import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

/**
 * Read backend port from <project-root>/.cache/work/.port
 */
function readBackendPort(): number {
  const projRoot = resolve(__dirname, '..');
  const portFile = join(projRoot, '.cache', 'work', '.port');
  try {
    if (existsSync(portFile)) {
      const txt = readFileSync(portFile, 'utf8').trim();
      const port = Number(txt.split(/\s+/)[0]);
      if (Number.isInteger(port) && port > 0) return port;
    }
  } catch {}
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
