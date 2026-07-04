package com.cadbbox.parser.web.dto;

/** Flat leaf-part bbox record, used for JSON/CSV export (Slice 7). */
public record PartBBox(
        String nodeId,
        String name,
        String productLabel,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        double centerX, double centerY, double centerZ,
        double sizeX, double sizeY, double sizeZ
) {}
