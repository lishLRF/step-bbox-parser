#!/usr/bin/env node
/**
 * Launch step-bbox-parser in dev mode with auto-chosen ports.
 *
 *   node scripts/dev.mjs
 *
 * What it does:
 *   1. Starts the Spring Boot backend (mvn spring-boot:run). Spring picks a free
 *      port (server.port=0) and writes it to <tmpdir>/step-bbox-parser/.port.
 *   2. Polls .port until the backend is up (max 60s).
 *   3. Starts the Vite dev server (npm run dev), which reads the same .port file
 *      to proxy /api at the right backend port, and itself picks a free port.
 *   4. Prints both URLs.
 *
 * Stop with Ctrl+C — both child processes are killed, Spring runs its @PreDestroy
 * (deleting .port), and both ports are released.
 */
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync, statSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(import.meta.url), '..', '..');
const backend = join(root, 'backend');
const frontend = join(root, 'frontend');
const isWin = process.platform === 'win32';

// Resolve the OS real temp dir. On Windows, Node's tmpdir() can differ from
// Java's java.io.tmpdir depending on env vars; we check both, plus the literal
// Windows %TEMP%/%LOCALAPPDATA% path the JVM uses.
function candidateTempDirs() {
  const dirs = [tmpdir()];
  if (process.platform === 'win32') {
    try {
      const winTemp = execSync('cmd /c echo %TEMP%', { encoding: 'utf8' }).trim();
      if (winTemp && winTemp !== '%TEMP%') dirs.push(winTemp);
    } catch {}
    if (process.env.LOCALAPPDATA) dirs.push(join(process.env.LOCALAPPDATA, 'Temp'));
  }
  return [...new Set(dirs)];
}

// Match the path the backend writes (see application.yml + PortPublisher):
// <tmpdir>/step-bbox-parser/work/.port
function candidatePortFiles() {
  return candidateTempDirs().map((d) => join(d, 'step-bbox-parser', 'work', '.port'));
}
let portFile = candidatePortFiles()[0];

// Clean any stale .port from a previous crashed run.
for (const f of candidatePortFiles()) { try { rmSync(f, { force: true }); } catch {} }

function log(tag, msg) { console.log(`[${tag}] ${msg}`); }

log('dev', `root=${root}`);
log('dev', 'starting backend…');
// Run the backend from the packaged jar. We avoid `mvn spring-boot:run`
// because that Mojo intermittently fails with ClassNotFoundException on
// this host (its forked process doesn't always pick up target/classes). The
// jar is rebuilt on demand — if it's missing or stale, we rebuild it first.
const jarPath = join(backend, 'target', 'step-bbox-parser-0.1.0-SNAPSHOT.jar');
let needsBuild = true;
try {
  const st = statSync(jarPath);
  // Rebuild if the jar is older than any main source file.
  const srcDir = join(backend, 'src', 'main', 'java');
  let newestSrc = 0;
  for (const f of readdirSync(srcDir, { recursive: true })) {
    const p = join(srcDir, String(f));
    try { if (p.endsWith('.java')) newestSrc = Math.max(newestSrc, statSync(p).mtimeMs); } catch {}
  }
  needsBuild = st.mtimeMs < newestSrc;
} catch { /* jar missing */ }
if (needsBuild) {
  log('dev', 'rebuilding backend jar (mvn package -DskipTests)…');
  const build = spawnSync(isWin ? 'mvn.cmd' : 'mvn',
    ['-q', '-B', 'package', '-DskipTests'],
    { cwd: backend, shell: isWin, stdio: 'inherit' });
  if (build.status !== 0) { log('dev', 'backend build failed'); process.exit(1); }
}
const be = spawn('java', ['-jar', jarPath],
    { cwd: backend, shell: false, stdio: 'inherit' });

// Wait for any of the candidate .port files to appear (max 60s).
const deadline = Date.now() + 60_000;
let backendPort = null;
while (Date.now() < deadline) {
  for (const f of candidatePortFiles()) {
    if (existsSync(f)) {
      try {
        const txt = readFileSync(f, 'utf8').trim();
        const p = Number(txt.split(/\s+/)[0]);
        if (Number.isInteger(p) && p > 0) { backendPort = p; portFile = f; break; }
      } catch {}
    }
  }
  if (backendPort) break;
  await sleep(500);
}
if (!backendPort) {
  log('dev', 'backend did not advertise a port within 60s — aborting');
  be.kill('SIGTERM');
  process.exit(1);
}
log('dev', `backend is up on http://localhost:${backendPort}`);

log('dev', 'starting frontend (npm run dev)…');
const fe = spawn(isWin ? 'npm.cmd' : 'npm', ['run', 'dev'],
    { cwd: frontend, shell: isWin, stdio: 'inherit' });

// Forward Ctrl+C to both children, then exit.
async function shutdown() {
  log('dev', 'shutting down…');
  // Try graceful backend shutdown via actuator (works on Windows where taskkill
  // can't send a gentle signal to a console app). This lets @PreDestroy run and
  // delete the .port file.
  try {
    if (backendPort) {
      await fetch(`http://localhost:${backendPort}/actuator/shutdown`, { method: 'POST' }).catch(() => {});
    }
  } catch {}
  // Stop the frontend dev server.
  try { fe.kill('SIGTERM'); } catch {}
  // Force-kill both after a grace period.
  setTimeout(() => {
    try { fe.kill('SIGKILL'); } catch {}
    try { be.kill('SIGKILL'); } catch {}
    process.exit(0);
  }, 4000);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
be.on('exit', (code) => { log('backend', `exited (${code})`); shutdown(); });
fe.on('exit', (code) => { log('frontend', `exited (${code})`); shutdown(); });

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }
