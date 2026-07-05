# Mesh generation environment setup

The center-column "real geometry" view needs STEP→GLB tessellation, which
requires `cadquery` (OCCT) + `trimesh`. These live in a dedicated conda env.

## One-time setup (run this yourself — it takes ~15-30 min and must not be interrupted)

The OCCT package is ~500 MB; conda's `Executing transaction` (hard-linking)
cannot be interrupted or it rolls back. Run in a real terminal, not under a
timeout:

```bash
conda create -y -n step-mesh -c conda-forge --override-channels -c conda-forge \
    python=3.11 cadquery trimesh numpy
```

This creates the env at `Z:\conda_envs\step-mesh` (already added to conda's
`envs_dirs`).

## Verify

```bash
Z:/conda_envs/step-mesh/python.exe -c "import cadquery, trimesh; print('ok', cadquery.__version__, trimesh.__version__)"
```

Expect: `ok 2.4.x 4.x.x`

## Smoke-test the converter standalone

```bash
Z:/conda_envs/step-mesh/python.exe scripts/step_to_mesh.py \
    samples/step/multi-asm.stp /tmp/test.glb
```

Expect a `OK: N verts, M faces -> /tmp/test.glb` line and a non-empty GLB.

## How the backend finds the env

`backend/src/main/resources/application.yml` points `mesh.python-exe` at
`Z:/conda_envs/step-mesh/python.exe` by default. Override with the
`MESH_PYTHON_EXE` env var if you put the env elsewhere.

Once the env exists, the `/api/models/{id}/mesh` endpoint and the frontend's
center column will work end-to-end (first request is slow — OCCT tessellates
the full assembly; subsequent requests hit the on-disk GLB cache).
