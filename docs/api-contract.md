# REST API Contract (DRAFT)

Base URL: `/api`. All responses are JSON (`application/json`), UTF-8.

## Conventions

- Identifiers are opaque strings (UUID v4).
- Coordinates are in the unit declared by the model's `metadata.unit`
  (default millimeter for STEP).
- All bounding boxes are expressed in **assembly-root** coordinates.
- Errors use RFC 9457 `application/problem+json`.

## Endpoints

### POST /models/upload

Upload and parse a STEP file.

- **Request**: `multipart/form-data`, field `file` (`.stp` / `.step`).
- **Response** `200 OK`: [`ModelMetadata`](#modelmetadata)
- **Errors**: `400` bad file, `413` too large, `422` unparseable STEP.

### GET /models/{id}/metadata

- **Response**: [`ModelMetadata`](#modelmetadata)

### GET /models/{id}/tree

Returns the full assembly tree with per-node bounding boxes.

- **Response**: [`TreeNode`](#treenode)

### GET /models/{id}/bbox

Flat list of leaf-part bounding boxes (convenient for CSV export).

- **Response**: `[`[`PartBBox`](#partbbox)`]`

### DELETE /models/{id}

Remove a cached model and its artifacts.

- **Response**: `204 No Content`

## Schemas

### ModelMetadata
```json
{
  "id": "uuid",
  "fileName": "gmc2550wrs-0001.stp",
  "sourceCadSystem": "CREO PARAMETRIC",
  "schema": ["CONFIG_CONTROL_DESIGN", "SHAPE_APPEARANCE_LAYERS_GROUPS"],
  "unit": "MILLIMETER",
  "parsedAt": "2026-07-04T10:00:00Z",
  "partCount": 412,
  "assemblyCount": 18
}
```

### TreeNode
```json
{
  "id": "node-1",
  "name": "GMC2550WRS主机-刀库",
  "type": "ASSEMBLY",
  "productLabel": "GMC2550WRS-0001",
  "transform": null,
  "boundingBox": null,
  "children": [ /* … */ ]
}
```

### PartBBox
```json
{
  "nodeId": "node-42",
  "name": "11301上牙盘",
  "productLabel": "11301",
  "min": { "x": 120.0, "y": -40.0, "z": 0.0 },
  "max": { "x": 200.0, "y":  40.0, "z": 60.0 },
  "center": { "x": 160.0, "y": 0.0, "z": 30.0 },
  "size": { "x": 80.0, "y": 80.0, "z": 60.0 }
}
```

## Vec3
```json
{ "x": 0.0, "y": 0.0, "z": 0.0 }
```
