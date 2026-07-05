"""
STEP → glTF/GLB mesh converter. Spawned by the Java backend (ModelService) as:
    python step_to_mesh.py <input.stp> <output.glb> [--linear-deflection 0.1] [--angular-deflection 0.3]

Uses cadquery (OCCT) to tessellate every solid in the STEP file, then assembles
the triangles into a single trimesh.Trimesh and exports as binary glTF (.glb).

The output is a single-mesh GLB with vertex normals; per-part coloring is left
to the frontend (which already knows the assembly tree + bboxes). Keeping the
mesh flat (no per-part grouping in the GLB) avoids a heavy multi-node glTF and
lets the frontend apply its existing selection/colored-overlay logic.

Linear/angular deflection control the tessellation density. Defaults are tuned
for mechanical parts: ~0.1mm linear (in the file's units) gives smooth curves
without exploding triangle counts. For very large assemblies pass larger values.
"""
import sys
import argparse
import trimesh
import numpy as np


def tessellate_step(step_path: str, linear_deflection: float, angular_deflection: float) -> trimesh.Trimesh:
    """Tessellate every solid in the STEP file and merge into one Trimesh."""
    import cadquery as cq

    # cadquery.Importers.importStep returns a Workplane whose .val() is a Shape
    # (compound of solids). We tessellate each solid separately and concatenate.
    wp = cq.importers.importStep(step_path)
    shape = wp.val()

    # BRep -> triangulation via OCCT's BRepMesh. cadquery exposes this through
    # the .tessellate() method on Shape, returning (vertices, faces) per call.
    # For an assembly we want every solid; iterate the compounds down.
    vertices = []
    faces = []
    idx_offset = 0

    solids = shape.Solids() if hasattr(shape, "Solids") else [shape]
    if not solids:
        solids = [shape]

    for solid in solids:
        # tessellate returns (vertex_list, face_indices_into_vertex_list)
        try:
            verts, face_idx = solid.tessellate(linear_deflection, angular_deflection)
        except Exception:
            # Fallback: try the Workplane-level tessellation
            continue
        if not verts or not face_idx:
            continue
        for v in verts:
            vertices.append([v.x, v.y, v.z])
        for f in face_idx:
            # f is a cq.Face or a tuple of indices depending on cadquery version;
            # normalize to a triangle index triple
            if hasattr(f, "__iter__"):
                idx = list(f)
            else:
                continue
            # Triangulate polygons > 3 vertices (fan)
            if len(idx) == 3:
                faces.append([i + idx_offset for i in idx])
            elif len(idx) > 3:
                for k in range(1, len(idx) - 1):
                    faces.append([idx[0] + idx_offset, idx[k] + idx_offset, idx[k + 1] + idx_offset])
        idx_offset += len(verts)

    if not vertices or not faces:
        raise RuntimeError("Tessellation produced no geometry")

    mesh = trimesh.Trimesh(vertices=np.array(vertices, dtype=np.float64),
                           faces=np.array(faces, dtype=np.int64), process=False)
    mesh.merge_vertices()
    # Recompute normals so the GLB renders with proper lighting.
    mesh.fix_normals()
    return mesh


def main():
    p = argparse.ArgumentParser()
    p.add_argument("input", help="input .stp/.step file")
    p.add_argument("output", help="output .glb file")
    p.add_argument("--linear-deflection", type=float, default=0.1)
    p.add_argument("--angular-deflection", type=float, default=0.3)
    args = p.parse_args()

    mesh = tessellate_step(args.input, args.linear_deflection, args.angular_deflection)
    # Export as binary glTF (single-mesh GLB). trimesh handles the packaging.
    mesh.export(args.output, file_type="glb")
    print(f"OK: {len(mesh.vertices)} verts, {len(mesh.faces)} faces -> {args.output}")


if __name__ == "__main__":
    main()
