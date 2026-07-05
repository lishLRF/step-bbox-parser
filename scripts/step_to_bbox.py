"""
STEP → per-part AABB JSON. Spawned by the Java backend at upload time.

Outputs progress lines to stdout: "PROGRESS: <done>/<total>" so the backend
can forward them to the frontend via SSE.

Uses OCP (OCCT) to load the STEP, tessellate every solid/shell, and compute
per-part local-frame AABBs.
"""
import sys
import argparse
import json
import numpy as np


def compute_part_bboxes(step_path: str) -> dict:
    from OCP.STEPControl import STEPControl_Reader
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopAbs import TopAbs_SOLID, TopAbs_SHELL, TopAbs_FACE
    from OCP.TopoDS import TopoDS
    from OCP.TopLoc import TopLoc_Location
    from OCP.BRep import BRep_Tool

    reader = STEPControl_Reader()
    status = reader.ReadFile(step_path.encode("utf-8") if isinstance(step_path, str) else step_path)
    if not status:
        raise RuntimeError(f"OCP could not read STEP file: {step_path}")
    reader.TransferRoots()
    shape = reader.OneShape()
    if shape is None or shape.IsNull():
        raise RuntimeError("STEP reader returned no shape")

    # Count solids first for progress reporting.
    count_exp = TopExp_Explorer(shape, TopAbs_SOLID)
    total_solids = 0
    while count_exp.More():
        total_solids += 1
        count_exp.Next()
    print(f"PROGRESS: 0/{total_solids}", flush=True)

    # Tessellate the whole shape.
    BRepMesh_IncrementalMesh(shape, 0.1, False, 0.3).Perform()

    triangulate = getattr(BRep_Tool, "Triangulation", None) or getattr(BRep_Tool, "Triangulation_s")

    # Walk every solid, compute its local AABB.
    result = {}
    part_idx = 0

    explorer = TopExp_Explorer(shape, TopAbs_SOLID)
    while explorer.More():
        subshape = explorer.Current()
        vertices = _collect_vertices(subshape, triangulate)
        if vertices and len(vertices) >= 3:
            arr = np.array(vertices)
            mn = arr.min(axis=0)
            mx = arr.max(axis=0)
            result[f"_solid_{part_idx}"] = {
                "min": [float(mn[0]), float(mn[1]), float(mn[2])],
                "max": [float(mx[0]), float(mx[1]), float(mx[2])],
                "vertexCount": len(vertices),
            }
        part_idx += 1
        if part_idx % 100 == 0:
            print(f"PROGRESS: {part_idx}/{total_solids}", flush=True)
        explorer.Next()

    print(f"PROGRESS: {part_idx}/{total_solids}", flush=True)

    # Overall bbox.
    all_verts = _collect_vertices(shape, triangulate)
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


def _collect_vertices(shape, triangulate) -> list:
    verts = []
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
    print(f"DONE: {len(bboxes)} parts -> {args.output}", flush=True)


if __name__ == "__main__":
    main()
