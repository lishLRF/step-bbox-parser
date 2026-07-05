package com.cadbbox.parser.web.api;

import com.cadbbox.parser.web.ModelService;
import com.cadbbox.parser.web.ModelService.BadFileException;
import com.cadbbox.parser.web.ModelService.ModelNotFoundException;
import com.cadbbox.parser.web.ModelService.StepParseException;
import com.cadbbox.parser.web.dto.ModelMetadata;
import com.cadbbox.parser.web.dto.PartBBox;
import com.cadbbox.parser.web.dto.TreeNode;
import com.cadbbox.parser.bbox.StepExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** REST API for uploading and inspecting STEP models. */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelService service;
    private final StepExporter stepExporter;
    private final com.cadbbox.parser.web.MeshService meshService;

    public ModelController(ModelService service, StepExporter stepExporter,
                           com.cadbbox.parser.web.MeshService meshService) {
        this.service = service;
        this.stepExporter = stepExporter;
        this.meshService = meshService;
    }

    @PostMapping("/upload")
    public ResponseEntity<ModelMetadata> upload(@RequestParam("file") MultipartFile file) throws IOException {
        ModelMetadata meta = service.upload(file);
        return ResponseEntity.ok(meta);
    }

    // ---- List cached models (previously parsed) ----
    @GetMapping("/cached")
    public List<Map<String, Object>> listCached() {
        return service.listCachedModels();
    }

    // ---- Load a cached model (skip re-parse) ----
    @PostMapping("/cached/{id}/load")
    public ModelMetadata loadCached(@PathVariable String id) {
        return service.loadCached(id);
    }

    @GetMapping("/{id}/tree")
    public TreeNode tree(@PathVariable String id) {
        return service.tree(id);
    }

    @GetMapping("/{id}/metadata")
    public ModelMetadata metadata(@PathVariable String id) {
        return service.metadata(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Slice 6: rename ----
    @PatchMapping("/{id}/nodes/{nodeId}/rename")
    public ResponseEntity<Void> rename(@PathVariable String id, @PathVariable String nodeId,
                                       @RequestBody Map<String, String> body) throws IOException {
        service.renameNode(id, nodeId, body.get("name"));
        return ResponseEntity.noContent().build();
    }

    // ---- Slice 7: bbox export (JSON) ----
    @GetMapping("/{id}/bbox")
    public List<PartBBox> bbox(@PathVariable String id) {
        return service.bboxList(id);
    }

    // ---- Slice 8: merge groups ----
    @PostMapping("/{id}/merge-groups")
    public ResponseEntity<Map<String, String>> createMerge(@PathVariable String id,
                                                           @RequestBody Map<String, Object> body) throws IOException {
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) body.get("memberIds");
        String name = (String) body.get("name");
        String gid = service.createMergeGroup(id, members, name);
        return ResponseEntity.ok(Map.of("id", gid));
    }

    @DeleteMapping("/{id}/merge-groups/{groupId}")
    public ResponseEntity<Void> deleteMerge(@PathVariable String id, @PathVariable String groupId) throws IOException {
        service.deleteMergeGroup(id, groupId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/merge-groups/{groupId}/rename")
    public ResponseEntity<Void> renameMerge(@PathVariable String id, @PathVariable String groupId,
                                            @RequestBody Map<String, String> body) throws IOException {
        service.renameMergeGroup(id, groupId, body.get("name"));
        return ResponseEntity.noContent().build();
    }

    // ---- Slice 9: export STEP ----
    @PostMapping("/{id}/export/step")
    public ResponseEntity<byte[]> exportStep(@PathVariable String id) {
        TreeNode tree = service.tree(id);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        stepExporter.export(tree, baos);
        byte[] bytes = baos.toByteArray();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"skeleton.stp\"")
                .contentType(MediaType.parseMediaType("application/step"))
                .body(bytes);
    }

    // ---- Real-geometry mesh (cadquery/trimesh → GLB) ----
    @GetMapping("/{id}/mesh")
    public ResponseEntity<byte[]> mesh(@PathVariable String id) throws IOException, InterruptedException {
        com.cadbbox.parser.web.ParsedModel model = service.requirePublic(id);
        byte[] glb = meshService.getOrGenerate(id, model.sourceStep());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"model.glb\"")
                .contentType(MediaType.parseMediaType("model/gltf-binary"))
                .body(glb);
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ProblemDetail> meshIoError(java.io.IOException e) {
        String detail = e.getMessage();
        // Truncate very long converter logs for the response body.
        if (detail != null && detail.length() > 500) detail = detail.substring(0, 500) + "...";
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "mesh-generation-failed",
                "网格生成失败: " + (detail != null ? detail : "unknown"));
    }

    // ---- Upload progress (polling REST) ----
    @GetMapping("/{id}/progress")
    public Map<String, String> progress(@PathVariable String id) {
        String prog = service.getUploadProgress(id);
        return Map.of("progress", prog != null ? prog : "unknown");
    }

    // ---- Cache cleanup ----
    @DeleteMapping("/cache")
    public Map<String, Object> cleanupCache(@RequestParam(defaultValue = "24") int maxAgeHours) {
        int removed = service.cleanupOldCaches(maxAgeHours);
        return Map.of("removed", removed, "maxAgeHours", maxAgeHours);
    }

    // ---- error mapping (RFC 9457 problem+json) ----

    @ExceptionHandler(BadFileException.class)
    public ResponseEntity<ProblemDetail> badFile(BadFileException e) {
        return problem(HttpStatus.BAD_REQUEST, "bad-file", e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large",
                "Uploaded file exceeds the maximum allowed size");
    }

    @ExceptionHandler(StepParseException.class)
    public ResponseEntity<ProblemDetail> unparseable(StepParseException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "unparseable-step", e.getMessage());
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(ModelNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "model-not-found", e.getMessage());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://step-bbox-parser/errors/" + type));
        p.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).body(p);
    }
}
