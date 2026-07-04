# Step BBox Parser

> Parse STEP (ISO-10303-21) CAD assembly files, extract the assembly/part tree,
> and compute a tight axis-aligned bounding box (AABB) for each part in accurate
> relative (assembly-root) coordinates — then visualize it in the browser.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-blue.svg)](https://react.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build](https://github.com/lishLRF/step-bbox-parser/actions/workflows/ci.yml/badge.svg)](.github/workflows/ci.yml)

---

## ✨ What it does

Given a `.stp` / `.step` file exported from SolidWorks, Creo, NX, CATIA, etc.,
this service:

1. **Streams & parses** the STEP `DATA` section (no heavy CAD kernel required).
2. **Builds the assembly tree** — the hierarchy of `PRODUCT_DEFINITION` →
   `NEXT_ASSEMBLY_USAGE_OCCURRENCE` relationships with instance transforms.
3. **Computes a bounding box per part** by accumulating every geometric entity
   (vertex / Cartesian point / B-rep surface) and transforming it up the tree
   into assembly-root coordinates.
4. **Serves a JSON tree** to a React + Three.js front end that renders the
   assembly tree and the bounding boxes as wireframe cuboids.

## 📁 Project layout

```
step-bbox-parser/
├── backend/                 # Java 21 + Spring Boot 3 (Maven)
│   ├── src/main/java/com/cadbbox/parser/
│   │   ├── step/            # STEP (ISO-10303-21) tokenizer & parser
│   │   ├── tree/            # Assembly tree builder
│   │   ├── bbox/            # Bounding-box computation
│   │   ├── web/             # REST controllers, DTOs
│   │   └── config/          # Spring configuration
│   └── src/test/...
├── frontend/                # React 18 + Vite + Three.js
│   ├── src/
│   │   ├── components/      # TreeView, BBoxViewer, ModelUploader
│   │   ├── pages/           # ViewerPage
│   │   ├── services/        # API client
│   │   ├── store/           # Zustand store
│   │   └── types/           # Shared TS types (mirror of backend DTOs)
│   └── public/
├── samples/                 # Example STEP files & expected bbox JSON
│   ├── step/
│   └── bbox-output/
├── docs/                    # Design docs, ADRs, PRD
├── scripts/                 # Helper scripts (format, generate-samples, …)
└── .github/                 # Issue templates, CI workflows, PR template
```

## 🚀 Quick start

### Prerequisites
- JDK 21
- Node.js 20+ (npm or pnpm)
- (Maven is bundled via `./mvnw` — no global install needed)

### One-command dev (recommended)

Both servers auto-pick **free ports** (nothing hard-coded; coexists with other
software). Backend writes its chosen port to a `.port` file; the launcher reads
it, then starts the frontend.

```bash
node scripts/dev.mjs        # Ctrl+C stops both cleanly and releases the ports
```

The script prints both URLs (e.g. `backend on http://localhost:12785`,
`frontend on http://localhost:5173`). Stop with **Ctrl+C** — the backend is
shut down gracefully via `/actuator/shutdown` so its port is released and the
`.port` file is cleaned up.

### Manual (two terminals)

```bash
# Terminal 1 — backend (auto-picks a free port, prints it)
cd backend
./mvnw spring-boot:run      # Windows: mvnw.cmd spring-boot:run

# Terminal 2 — frontend (reads the backend's port from .port, auto-picks its own)
cd frontend
npm install
npm run dev
```

## 🔌 API (draft)

| Method | Path                        | Description                                  |
|--------|-----------------------------|----------------------------------------------|
| POST   | `/api/models/upload`        | Upload a `.stp`/`.step` file, returns `id`   |
| GET    | `/api/models/{id}/tree`     | Assembly tree + per-node bbox as JSON        |
| GET    | `/api/models/{id}/bbox`     | Flat list of part bboxes (root coordinates)  |
| GET    | `/api/models/{id}/metadata` | Units, schema, source CAD system, mass props |
| DELETE | `/api/models/{id}`          | Remove a cached model                        |

See [`docs/api-contract.md`](docs/api-contract.md) for the full contract.

## 🧪 Testing

- **Backend**: JUnit 5 + AssertJ. Golden-file tests against `samples/step/*.stp`
  compared to `samples/bbox-output/*.json`.
- **Frontend**: Vitest + React Testing Library; Playwright for the viewer E2E.

```bash
cd backend && ./mvnw test
cd frontend && npm test
```

## 📜 License

MIT — see [LICENSE](LICENSE).
