"""
STEP → per-part AABB JSON. Spawned by the Java backend at upload time.

Uses OCP (OCCT) to load the STEP file and compute a local-frame AABB for every
solid/shell, keyed by product name. Output is a JSON map:
  {"part_name": {"min": [x,y,z], "max": [x,y,z]}, ...}

This replaces the text-based CARTESIAN_POINT DFS approach, which failed on
Creo's complex CDSR-linked geometry chains. OCCT handles all of that natively.
"""
import sys
import argparse
import json
import numpy as np


def compute_part_bboxes(step_path: str) -> dict:
    from OCP.STEPControl import STEPControl_Reader
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.BRep import BRep_Tool
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopAbs import TopAbs_SOLID, TopAbs_SHELL, TopAbs_FACE
    from OCP.TopLoc import TopLoc_Location
    from OCP.TopoDS import TopoDS
    from OCP.TColStd import TColStd_IndexedMapOfInteger
    from OCP.TopExp import TopExp_Explorer
    from OCP.BRepAdaptor import BRepAdaptor_Surface
    from OCP.gp import gp_Pnt

    reader = STEPControl_Reader()
    status = reader.ReadFile(step_path.encode("utf-8") if isinstance(step_path, str) else step_path)
    if not status:
        raise RuntimeError(f"OCP could not read STEP file: {step_path}")
    reader.TransferRoots()
    shape = reader.OneShape()
    if shape is None or shape.IsNull():
        raise RuntimeError("STEP reader returned no shape")

    # Tessellate to get vertex coordinates.
    BRepMesh_IncrementalMesh(shape, 0.1, False, 0.3).Perform()

    # Walk every solid and shell, compute its local AABB from triangulation vertices.
    # Group by product name if available; otherwise by solid/shell index.
    result = {}

    # Strategy: iterate over all solids first, then standalone shells not in solids.
    explorer_types = [
        (TopAbs_SOLID, "solid"),
        (TopAbs_SHELL, "shell"),
    ]

    part_idx = 0
    for topo_type, label in explorer_types:
        explorer = TopExp_Explorer(shape, topo_type)
        while explorer.More():
            subshape = explorer.Current()
            # Get bounding box of this sub-shape via triangulation.
            vertices = _collect_vertices(subshape)
            if vertices and len(vertices) >= 3:
                arr = np.array(vertices)
                mn = arr.min(axis=0)
                mx = arr.max(axis=0)
                name = f"_{label}_{part_idx}"
                result[name] = {
                    "min": [float(mn[0]), float(mn[1]), float(mn[2])],
                    "max": [float(mx[0]), float(mx[1]), float(mx[2])],
                    "vertexCount": len(vertices),
                }
                part_idx += 1
            explorer.Next()

    # Also compute the overall bounding box.
    all_verts = _collect_vertices(shape)
    if all_verts:
        arr = np.array(all_verts)
        mn = arr.min(axis=0)
        mx = arr.max(axis=0)
        result["__overall__"] = {
            "min": [float(mn[0]), float(mn[1]), float(mn[2])],
            "max": [float(mx[0]), float(mx[1]), float(mx[2])],
            "vertexCount": len(all_verts),
        }

    return result


def _collect_vertices(shape) -> list:
    """Collect all triangulated vertex coordinates from a shape."""
    from OCP.BRep import BRep_Tool
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopAbs import TopAbs_FACE
    from OCP.TopLoc import TopLoc_Location
    from OCP.TopoDS import TopoDS

    verts = []
    triangulate = getattr(BRep_Tool, "Triangulation", None) or getattr(BRep_Tool, "Triangulation_s")
    explorer = TopExp_Explorer(shape, TopAbs_FACE)
    seen_triangulations = set()
    while explorer.More():
        face = TopoDS.Face(explorer.Current())
        loc = TopLoc_Location()
        tri = triangulate(face, loc)
        if tri is not None and tri.NbNodes() > 0:
            tri_id = id(tri)
            if tri_id in seen_triangulations:
                explorer.Next()
                continue
            seen_triangulations.add(tri_id)
            trsf = loc.Transformation()
            n = tri.NbNodes()
            for i in range(1, n + 1):
                pnt = tri.Node(i)
                pnt.Transform(trsf)
                verts.append([pnt.X(), pnt.Y(), pnt.Z()])
        explorer.Next()
    return verts


def main():
    p = argparse.ArgumentParser()
    p.add_argument("input", help="input .stp/.step file")
    p.add_argument("output", help="output .json file")
    args = p.parse_args()

    bboxes = compute_part_bboxes(args.input)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(bboxes, f, ensure_ascii=False)
    print(f"OK: {len(bboxes)} parts -> {args.output}")


if __name__ == "__main__":
    main()
