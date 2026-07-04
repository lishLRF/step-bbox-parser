package com.cadbbox.parser.web.dto;

import java.util.List;

/** Metadata about a parsed model, returned by {@code POST /api/models/upload}. */
public record ModelMetadata(
        String id,
        String fileName,
        String sourceCadSystem,
        List<String> schema,
        String unit,
        String parsedAt,
        int partCount,
        int assemblyCount
) {}
