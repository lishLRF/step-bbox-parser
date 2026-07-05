"""
STEP → glTF/GLB mesh converter. Spawned by the Java backend (MeshService) as:
    python step_to_mesh.py <input.stp> <output.glb> [--linear-deflection 0.1] [--angular-deflection 0.3]

Uses OCP (the OCCT Python bindings, shipped with cadquery/cadquery-ocp) to load
the STEP file, run BRepMesh_IncrementalMesh on every solid & shell, harvest the
triangulations into a single trimesh.Trimesh, and export as binary glTF (.glb).

This is robust to both solids (closed volumes) and open shells (surfaces),
which matters because some Creo exports include MANIFOLD_SURFACE_SHAPE
representations that aren't closed solids — cadquery's high-level
.tessellate() only handles solids, so we go one layer down to OCCT.
"""
import sys
import argparse
import numpy as np


def tessellate_step(step_path: str, linear_deflection: float, angular_deflection: float) -> "trimesh.Trimesh":
    from OCP.STEPControl import STEPControl_Reader
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.BRep import BRep_Tool
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopAbs import TopAbs_FACE
    from OCP.TopLoc import TopLoc_Location
    from OCP.Poly import Poly_Triangulation
    import trimesh

    # 1. Load the STEP file.
    reader = STEPControl_Reader()
    status = reader.ReadFile(step_path.encode("utf-8") if isinstance(step_path, str) else step_path)
    if not status:
        raise RuntimeError(f"OCP could not read STEP file: {step_path}")
    reader.TransferRoots()
    shape = reader.OneShape()
    if shape is None:
        raise RuntimeError("STEP reader returned no shape")

    # 2. Tessellate the whole shape (handles solids, shells, and free faces).
    mesh = BRepMesh_IncrementalMesh(shape, linear_deflection, False, angular_deflection)
    mesh.Perform()

    # 3. Walk every face and harvest its triangulation.
    vertices = []
    faces = []
    base = 0

    from OCP.TopoDS import TopoDS

    explorer = TopExp_Explorer(shape, TopAbs_FACE)
    # BRep_Tool.Triangulation is exposed as a static method whose exact name
    # varies across OCP versions (Triangulation vs Triangulation_s).
    triangulate = getattr(BRep_Tool, "Triangulation", None) or getattr(BRep_Tool, "Triangulation_s")
    while explorer.More():
        # explorer.Current() returns a TopoDS_Shape; cast to TopoDS_Face.
        face = TopoDS.Face(explorer.Current())
        loc = TopLoc_Location()
        tri = triangulate(face, loc)
        if tri is not None and tri.NbNodes() > 0 and tri.NbTriangles() > 0:
            trsf = loc.Transformation()
            n_nodes = tri.NbNodes()
            n_tris = tri.NbTriangles()
            # Read nodes (transformed to absolute coordinates).
            for i in range(1, n_nodes + 1):
                pnt = tri.Node(i)
                pnt.Transform(trsf)
                vertices.append([pnt.X(), pnt.Y(), pnt.Z()])
            # Read triangles (1-based indices into the node array).
            for i in range(1, n_tris + 1):
                a, b, c = tri.Triangle(i).Get()  # returns (a, b, c)
                faces.append([a - 1 + base, b - 1 + base, c - 1 + base])
            base += n_nodes
        explorer.Next()

    if not vertices or not faces:
        raise RuntimeError("Tessellation produced no geometry (no triangulated faces found)")

    out = trimesh.Trimesh(
        vertices=np.array(vertices, dtype=np.float64),
        faces=np.array(faces, dtype=np.int64),
        process=False,
    )
    # merge_vertices / fix_normals need networkx; they're nice-to-have for
    # watertight cleanup but not required for GLB export. Skip if unavailable.
    try:
        out.merge_vertices()
        out.fix_normals()
    except ModuleNotFoundError:
        # networkx missing — compute simple vertex normals from face geometry.
        out._cache.clear()
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("input", help="input .stp/.step file")
    p.add_argument("output", help="output .glb file")
    p.add_argument("--linear-deflection", type=float, default=0.1)
    p.add_argument("--angular-deflection", type=float, default=0.3)
    args = p.parse_args()

    mesh = tessellate_step(args.input, args.linear_deflection, args.angular_deflection)
    mesh.export(args.output, file_type="glb")
    print(f"OK: {len(mesh.vertices)} verts, {len(mesh.faces)} faces -> {args.output}")


if __name__ == "__main__":
    main()
