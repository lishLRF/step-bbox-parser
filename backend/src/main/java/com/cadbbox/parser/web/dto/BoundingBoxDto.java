package com.cadbbox.parser.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An axis-aligned bounding box (root coordinates). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoundingBoxDto(Vec3 min, Vec3 max, Vec3 center, Vec3 size) {}
