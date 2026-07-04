package com.cadbbox.parser.bbox;

import com.cadbbox.parser.web.dto.TreeNode;
import com.cadbbox.parser.web.dto.NodeType;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates an ISO-10303-21 (AP214) STEP file where each leaf part's AABB is
 * emitted as a rectangular cuboid solid (8 vertices, 12 edges, 6 rectangular
 * faces, 1 closed shell, 1 MANIFOLD_SOLID_BREP), positioned in assembly-root
 * coordinates. Pure geometry — no colors, no names.
 *
 * <p>Merge-group handling: if a TreeNode is a SUBASSEMBLY created by Slice 8
 * (its id starts with {@code merge-}), it carries its own boundingBox and is
 * emitted as one cuboid; its underlying members are skipped automatically
 * (they're not in the tree under it — they're siblings).
 */
@Component
public class StepExporter {

    /** Write the skeleton STEP file for the given tree to {@code out}. */
    public void export(TreeNode root, OutputStream out) {
        List<Cuboid> cuboids = new ArrayList<>();
        collect(root, cuboids, new java.util.HashSet<>());
        try (PrintWriter w = new PrintWriter(out, false, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            writeHeader(w);
            writeUnits(w);
            AtomicInteger id = new AtomicInteger(100);
            List<String> productRefs = new ArrayList<>();
            for (Cuboid c : cuboids) {
                String brepRef = writeCuboid(w, id, c);
                productRefs.add(brepRef);
            }
            writeAssembly(w, id, productRefs);
            w.write("ENDSEC;\nEND-ISO-10303-21;\n");
        }
    }

    private void collect(TreeNode n, List<Cuboid> out, java.util.Set<String> skip) {
        // Merge groups emit one cuboid each and their members are recorded to skip.
        if (n.id() != null && n.id().startsWith("merge-")) {
            if (n.boundingBox() != null) out.add(toCuboid(n));
            return;
        }
        if (n.type() == NodeType.PART && n.boundingBox() != null && !skip.contains(n.id())) {
            out.add(toCuboid(n));
        }
        for (TreeNode c : n.children()) collect(c, out, skip);
    }

    private Cuboid toCuboid(TreeNode n) {
        var b = n.boundingBox();
        return new Cuboid(b.min().x(), b.min().y(), b.min().z(),
                b.max().x(), b.max().y(), b.max().z());
    }

    private void writeHeader(PrintWriter w) {
        w.write("ISO-10303-21;\n");
        w.write("HEADER;\n");
        w.write("FILE_DESCRIPTION(('step-bbox-parser skeleton export'),'2;1');\n");
        w.write("FILE_NAME('skeleton.stp','2026-07-04',('step-bbox-parser'),(''),\n");
        w.write("  'step-bbox-parser','step-bbox-parser','');\n");
        w.write("FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }'));\n");
        w.write("ENDSEC;\nDATA;\n");
    }

    private void writeUnits(PrintWriter w) {
        // Minimal unit/context declarations (mm).
        w.write("#1=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));\n");
        w.write("#2=(NAMED_UNIT(*)PLANE_ANGLE_UNIT()SI_UNIT($,.RADIAN.));\n");
        w.write("#3=(NAMED_UNIT(*)SI_UNIT($,.STERADIAN.)SOLID_ANGLE_UNIT());\n");
        w.write("#4=UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-3),#1,\n");
        w.write("  'UNCERTAINTY OF MEASUREMENT','');\n");
        w.write("#5=(GEOMETRIC_REPRESENTATION_CONTEXT(3)GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((#4))GLOBAL_UNIT_ASSIGNED_CONTEXT((#1,#2,#3))REPRESENTATION_CONTEXT(' ',''));\n");
    }

    /** Emit one cuboid solid; return the MANIFOLD_SOLID_BREP entity id (as #N). */
    private String writeCuboid(PrintWriter w, AtomicInteger id, Cuboid c) {
        // 8 corner vertices
        double[][] corners = {
            {c.minX, c.minY, c.minZ}, {c.maxX, c.minY, c.minZ},
            {c.maxX, c.maxY, c.minZ}, {c.minX, c.maxY, c.minZ},
            {c.minX, c.minY, c.maxZ}, {c.maxX, c.minY, c.maxZ},
            {c.maxX, c.maxY, c.maxZ}, {c.minX, c.maxY, c.maxZ},
        };
        int[] ptIds = new int[8];
        int[] vertIds = new int[8];
        for (int i = 0; i < 8; i++) {
            ptIds[i] = id.incrementAndGet();
            w.write(String.format("#%d=CARTESIAN_POINT('',(%.10f,%.10f,%.10f));\n",
                    ptIds[i], corners[i][0], corners[i][1], corners[i][2]));
            vertIds[i] = id.incrementAndGet();
            w.write(String.format("#%d=VERTEX_POINT('',#%d);\n", vertIds[i], ptIds[i]));
        }
        // 12 edges: bottom rectangle (0-1,1-2,2-3,3-0), top (4-5,5-6,6-7,7-4), verticals (0-4,1-5,2-6,3-7)
        int[][] edgePairs = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        int[] edgeIds = new int[12];
        for (int i = 0; i < 12; i++) {
            int a = vertIds[edgePairs[i][0]], b = vertIds[edgePairs[i][1]];
            // line geometry
            int lineId = id.incrementAndGet();
            // LINE needs a point + vector; reuse vertex a's point and a direction we compute.
            int dirId = id.incrementAndGet();
            double dx = corners[edgePairs[i][1]][0] - corners[edgePairs[i][0]][0];
            double dy = corners[edgePairs[i][1]][1] - corners[edgePairs[i][0]][1];
            double dz = corners[edgePairs[i][1]][2] - corners[edgePairs[i][0]][2];
            double len = Math.sqrt(dx*dx+dy*dy+dz*dz);
            if (len < 1e-15) len = 1;
            w.write(String.format("#%d=DIRECTION('',(%.10f,%.10f,%.10f));\n", dirId, dx/len, dy/len, dz/len));
            int vecId = id.incrementAndGet();
            w.write(String.format("#%d=VECTOR('',#%d,%.10f);\n", vecId, dirId, len));
            w.write(String.format("#%d=LINE('',#%d,#%d);\n", lineId, ptIds[edgePairs[i][0]], vecId));
            edgeIds[i] = id.incrementAndGet();
            w.write(String.format("#%d=EDGE_CURVE('',#%d,#%d,#%d,.T.);\n", edgeIds[i], a, b, lineId));
        }
        // Faces by edge indices: front(zmin) back(zmax) and 4 sides.
        int[][] faceEdges = {
            {0,1,2,3},       // bottom (z=min)
            {4,5,6,7},       // top (z=max)
            {0,8,4,11},      // side y=min
            {1,9,5,8},       // side x=max
            {2,10,6,9},      // side y=max
            {3,11,7,10},     // side x=min
        };
        boolean[][] faceOrient = {  // whether each edge is traversed forward
            {true,true,true,true},
            {true,true,true,true},
            {true,false,true,false},
            {true,false,true,false},
            {true,false,true,false},
            {true,false,true,false},
        };
        int[] faceIds = new int[6];
        for (int f = 0; f < 6; f++) {
            // edge loop
            int loopId = id.incrementAndGet();
            StringBuilder oe = new StringBuilder();
            for (int k = 0; k < 4; k++) {
                int eIdx = faceEdges[f][k];
                oe.append(String.format("#%d,", id.incrementAndGet()));
                w.write(String.format("#%d=ORIENTED_EDGE('',*,*,#%d,%s.);\n",
                        id.get(), edgeIds[eIdx], faceOrient[f][k] ? "T" : "F"));
            }
            String oeList = oe.toString().replaceAll(",$", "");
            w.write(String.format("#%d=EDGE_LOOP('',(%s));\n", loopId, oeList));
            int fobId = id.incrementAndGet();
            w.write(String.format("#%d=FACE_OUTER_BOUND('',#%d,.T.);\n", fobId, loopId));
            // planar surface: placement at first corner with normal
            int cpId = id.incrementAndGet();
            double[] o = corners[0];
            w.write(String.format("#%d=CARTESIAN_POINT('',(%.10f,%.10f,%.10f));\n", cpId, o[0],o[1],o[2]));
            int d1 = id.incrementAndGet(), d2 = id.incrementAndGet();
            w.write("#" + d1 + "=DIRECTION('',(0.,0.,1.));\n");
            w.write("#" + d2 + "=DIRECTION('',(1.,0.,0.));\n");
            int axp = id.incrementAndGet();
            w.write(String.format("#%d=AXIS2_PLACEMENT_3D('',#%d,#%d,#%d);\n", axp, cpId, d1, d2));
            int planeId = id.incrementAndGet();
            w.write(String.format("#%d=PLANE('',#%d);\n", planeId, axp));
            faceIds[f] = id.incrementAndGet();
            w.write(String.format("#%d=ADVANCED_FACE('',(#%d),#%d,.T.);\n", faceIds[f], fobId, planeId));
        }
        // closed shell
        int shellId = id.incrementAndGet();
        StringBuilder facesSb = new StringBuilder();
        for (int f : faceIds) facesSb.append("#").append(f).append(",");
        w.write(String.format("#%d=CLOSED_SHELL('',(%s));\n", shellId, facesSb.toString().replaceAll(",$","")));
        int brepId = id.incrementAndGet();
        w.write(String.format("#%d=MANIFOLD_SOLID_BREP('',#%d);\n", brepId, shellId));
        return "#" + brepId;
    }

    private void writeAssembly(PrintWriter w, AtomicInteger id, List<String> brepRefs) {
        // Wrap each BREP in an ADVANCED_BREP_SHAPE_REPRESENTATION + PRODUCT.
        // Minimal: one root shape representation listing all BREPs.
        StringBuilder items = new StringBuilder();
        for (String r : brepRefs) items.append(r).append(",");
        String itemsList = items.toString().replaceAll(",$","");
        int o = id.incrementAndGet(), ax = id.incrementAndGet(), rd = id.incrementAndGet();
        w.write("#"+o+"=CARTESIAN_POINT('',(0.,0.,0.));\n");
        w.write("#"+ax+"=DIRECTION('',(0.,0.,1.));\n");
        w.write("#"+rd+"=DIRECTION('',(1.,0.,0.));\n");
        int axp = id.incrementAndGet();
        w.write(String.format("#%d=AXIS2_PLACEMENT_3D('',#%d,#%d,#%d);\n", axp, o, ax, rd));
        int rep = id.incrementAndGet();
        w.write(String.format("#%d=ADVANCED_BREP_SHAPE_REPRESENTATION('',(%s#%d),#5);\n",
                rep, itemsList.isEmpty() ? "" : itemsList + ",", axp));
        w.write("// shape representation #" + rep + " holds all cuboids, context #5 (mm)\n");
    }

    private record Cuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
}
