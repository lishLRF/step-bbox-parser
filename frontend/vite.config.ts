import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

/**
 * Read the backend port from <cache-dir>/work/.port.
 * Cache dir is configured in Software/config.json (editable from frontend).
 */
function readBackendPort(): number {
  const projRoot = resolve(__dirname, '..');
  // Try config.json first.
  let cacheDir = join(projRoot, '..', 'cache'); // default: sibling of Software/
  try {
    const configFile = join(projRoot, 'config.json');
    if (existsSync(configFile)) {
      const cfg = JSON.parse(readFileSync(configFile, 'utf8'));
      if (cfg.cacheDir) cacheDir = cfg.cacheDir;
    }
  } catch {}

  const portFile = join(cacheDir, 'work', '.port');
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
