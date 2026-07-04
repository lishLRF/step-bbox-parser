# Contributing to Step BBox Parser

Thanks for your interest! This guide will get you set up and productive.

## 🧭 Project layout at a glance

| Area        | Path        | Stack                                |
|-------------|-------------|--------------------------------------|
| Backend     | `backend/`  | Java 21, Spring Boot 3, Maven        |
| Frontend    | `frontend/` | React 18, Vite, TypeScript, Three.js |
| Samples     | `samples/`  | Example STEP files + expected JSON   |
| Design docs | `docs/`     | ADRs, PRD, api contract              |

See [`README.md`](README.md) for the full layout.

## 🔧 Local setup

1. **Fork & clone** the repo.
2. **Backend**:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
3. **Frontend** (new terminal):
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
4. Open <http://localhost:5173>. The Vite dev server proxies `/api` to `:8080`.

## 📋 Before you submit a PR

- [ ] Code compiles and `./mvnw test` passes (backend) / `npm test` passes (frontend).
- [ ] New behavior is covered by a test. Prefer golden-file tests for parsing,
      and contract tests for the REST API.
- [ ] No large binaries committed. Put sample STEP files under `samples/step/`
      and keep them small (<5 MB) or use Git LFS.
- [ ] Public API changes are reflected in [`docs/api-contract.md`](docs/api-contract.md).
- [ ] Commit message follows [Conventional Commits](https://www.conventionalcommits.org/):
      `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.

## 🧪 Testing strategy

- **Parsing**: golden-file tests — each `samples/step/*.stp` has a matching
  `samples/bbox-output/*.json`; the parser output must match it bit-for-bit
  (after JSON normalization).
- **REST API**: `@SpringBootTest` + MockMvc contract tests over JSON snapshots.
- **Frontend**: Vitest unit tests for components; Playwright for the upload →
  render E2E flow.

## 🌿 Branching

- `main` — always green, deployable.
- Feature branches: `feat/<short-desc>`.
- Bugfix branches: `fix/<short-desc>`.

## 💬 Communication

- Open an issue before starting large work.
- Use Discussions for questions.

## 📜 Code of Conduct

Be kind. See [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
