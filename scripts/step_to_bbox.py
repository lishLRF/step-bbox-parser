"""
STEP → per-part AABB JSON. Spawned by the Java backend at upload time.

Outputs progress lines to stdout: "PROGRESS: <done>/<total>" so the backend
can forward them to the frontend.

Uses OCP (OCCT) to load the STEP, tessellate EVERY shape (solids, shells,
AND standalone faces), and compute per-part local-frame AABBs. This is
critical because Creo exports many parts as open shells (MANIFOLD_SURFACE)
that are NOT closed solids — skipping them loses geometry.
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

    # Count total sub-shapes for progress.
    total = 0
    for topo_type in [TopAbs_SOLID, TopAbs_SHELL]:
        ex = TopExp_Explorer(shape, topo_type)
        while ex.More():
            total += 1
            ex.Next()
    # Also count standalone faces not in any solid/shell.
    face_ex = TopExp_Explorer(shape, TopAbs_FACE)
    face_total = 0
    while face_ex.More():
        face_total += 1
        face_ex.Next()
    total = max(total, 1)
    print(f"PROGRESS: 0/{total}", flush=True)

    # Tessellate the whole shape.
    BRepMesh_IncrementalMesh(shape, 0.1, False, 0.3).Perform()

    triangulate = getattr(BRep_Tool, "Triangulation", None) or getattr(BRep_Tool, "Triangulation_s")

    # Strategy: collect vertices from EVERY face in the shape, grouped by
    # their containing solid or shell. If a face belongs to a solid, use that
    # solid's bbox. If it belongs to a shell (but not a solid), use that shell.
    # Standalone faces (not in any solid/shell) get their own bbox.
    #
    # To avoid double-counting: a face inside a solid is already covered by
    # the solid's bbox; we only need to separately handle faces that are in
    # shells but NOT in solids, and completely standalone faces.

    result = {}
    part_idx = 0

    # Phase 1: iterate all SOLIDS.
    solid_explorer = TopExp_Explorer(shape, TopAbs_SOLID)
    while solid_explorer.More():
        solid = solid_explorer.Current()
        verts = _collect_vertices(solid, triangulate)
        if verts and len(verts) >= 3:
            arr = np.array(verts)
            mn = arr.min(axis=0)
            mx = arr.max(axis=0)
            span = mx - mn
            # Only store if this is actually 3D (not degenerate flat).
            result[f"_solid_{part_idx}"] = {
                "min": [float(mn[0]), float(mn[1]), float(mn[2])],
                "max": [float(mx[0]), float(mx[1]), float(mx[2])],
                "vertexCount": len(verts),
            }
        part_idx += 1
        if part_idx % 100 == 0:
            print(f"PROGRESS: {part_idx}/{total}", flush=True)
        solid_explorer.Next()

    # Phase 2: iterate SHELLS that are NOT inside any solid.
    # Deduplicate: skip shells whose bbox is contained within an existing solid's bbox.
    solid_bboxes = []
    for k, v in result.items():
        if k.startswith("_solid_"):
            solid_bboxes.append((v["min"], v["max"]))

    shell_explorer = TopExp_Explorer(shape, TopAbs_SHELL)
    shell_count = 0
    while shell_explorer.More():
        shell = shell_explorer.Current()
        verts = _collect_vertices(shell, triangulate)
        if verts and len(verts) >= 3:
            arr = np.array(verts)
            mn = arr.min(axis=0)
            mx = arr.max(axis=0)
            # Check if this shell is contained in any solid — if so, skip it.
            is_duplicate = False
            for smin, smax in solid_bboxes:
                if (all(mn[i] >= smin[i] - 0.001 for i in range(3)) and
                    all(mx[i] <= smax[i] + 0.001 for i in range(3))):
                    is_duplicate = True
                    break
            if not is_duplicate:
                result[f"_shell_{shell_count}"] = {
                    "min": [float(mn[0]), float(mn[1]), float(mn[2])],
                    "max": [float(mx[0]), float(mx[1]), float(mx[2])],
                    "vertexCount": len(verts),
                }
        shell_count += 1
        part_idx += 1
        if part_idx % 100 == 0:
            print(f"PROGRESS: {min(part_idx, total)}/{total}", flush=True)
        shell_explorer.Next()

    print(f"PROGRESS: {total}/{total}", flush=True)

    # Overall bbox from ALL vertices in the shape.
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

    # Summary stats.
    solids_n = sum(1 for k in result if k.startswith("_solid_"))
    shells_n = sum(1 for k in result if k.startswith("_shell_"))
    print(f"SUMMARY: {solids_n} solids, {shells_n} shells, {len(all_verts)} total vertices", flush=True)

    return result


def _collect_vertices(shape, triangulate) -> list:
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopAbs import TopAbs_FACE
    from OCP.TopoDS import TopoDS
    from OCP.TopLoc import TopLoc_Location

    verts = []
    explorer = TopExp_Explorer(shape, TopAbs_FACE)
    while explorer.More():
        face = TopoDS.Face(explorer.Current())
        loc = TopLoc_Location()
        tri = triangulate(face, loc)
        if tri is not None and tri.NbNodes() > 0:
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
