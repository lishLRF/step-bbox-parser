package com.cadbbox.parser.tree;

/**
 * A 4x4 homogeneous transform (row-major) representing an assembly instance's
 * placement relative to its parent. Built from an {@code AXIS2_PLACEMENT_3D}
 * (origin + axis + refDirection) per ISO-10303-42 conventions.
 */
public final class Transform4 {

    /** Identity transform. */
    public static final Transform4 IDENTITY = new Transform4(
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1);

    /** Row-major 4x4 matrix. */
    public final double[] m;

    public Transform4(double... m) {
        if (m.length != 16) throw new IllegalArgumentException("need 16 elements, got " + m.length);
        this.m = m;
    }

    /** Apply this transform to a point (x,y,z) → new double[]{x',y',z'}. */
    public double[] apply(double x, double y, double z) {
        return new double[]{
                m[0] * x + m[1] * y + m[2] * z + m[3],
                m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11],
        };
    }

    /** Compose: returns this ∘ other (apply other first, then this). */
    public Transform4 compose(Transform4 other) {
        double[] r = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += this.m[row * 4 + k] * other.m[k * 4 + col];
                }
                r[row * 4 + col] = s;
            }
        }
        return new Transform4(r);
    }

    /**
     * Build an {@code AXIS2_PLACEMENT_3D} transform from origin + axis(z) + refDirection(x).
     *
     * <p>Per ISO 10303-42 the placement defines a local frame: {@code axis} is
     * the local Z, {@code refDirection} constrains the local X (it's projected
     * onto the plane perpendicular to Z). Local Y = Z × X.
     *
     * <p>The returned matrix is <b>row-major</b>: each axis vector sits in its
     * own row (row 0 = local X, row 1 = local Y, row 2 = local Z, row 3 = origin
     * in the translation column), so that {@link #apply} reads naturally.
     */
    public static Transform4 fromAxis2Placement(double[] origin, double[] axis, double[] refDir) {
        double[] z = normalize(axis);
        double[] xRaw = refDir;
        // Project refDir onto plane perpendicular to z to get a clean X.
        double dot = xRaw[0] * z[0] + xRaw[1] * z[1] + xRaw[2] * z[2];
        double[] x = normalize(new double[]{xRaw[0] - dot * z[0], xRaw[1] - dot * z[1], xRaw[2] - dot * z[2]});
        double[] y = cross(z, x);
        double[] o = origin;
        // Row-major:
        //   row 0 (local X): x[0] x[1] x[2] | o[0]
        //   row 1 (local Y): y[0] y[1] y[2] | o[1]
        //   row 2 (local Z): z[0] z[1] z[2] | o[2]
        //   row 3          : 0    0    0    | 1
        return new Transform4(
                x[0], x[1], x[2], o[0],
                y[0], y[1], y[2], o[1],
                z[0], z[1], z[2], o[2],
                0,    0,    0,    1);
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-15) return new double[]{0, 0, 1};
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    @Override
    public String toString() {
        return "T4" + java.util.Arrays.toString(m);
    }
}
