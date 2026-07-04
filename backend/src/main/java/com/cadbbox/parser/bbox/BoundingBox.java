package com.cadbbox.parser.bbox;

/** An axis-aligned bounding box in assembly-root coordinates. */
public record BoundingBox(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {

    public double sizeX() { return maxX - minX; }
    public double sizeY() { return maxY - minY; }
    public double sizeZ() { return maxZ - minZ; }

    public double centerX() { return (minX + maxX) / 2.0; }
    public double centerY() { return (minY + maxY) / 2.0; }
    public double centerZ() { return (minZ + maxZ) / 2.0; }

    /** A degenerate box built from a single point. */
    public static BoundingBox point(double x, double y, double z) {
        return new BoundingBox(x, y, z, x, y, z);
    }

    /** Expand this box to include the given point. */
    public BoundingBox include(double x, double y, double z) {
        return new BoundingBox(
                Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z),
                Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
    }

    public boolean isFinite() {
        return Double.isFinite(minX) && Double.isFinite(maxX)
                && Double.isFinite(minY) && Double.isFinite(maxY)
                && Double.isFinite(minZ) && Double.isFinite(maxZ);
    }
}
