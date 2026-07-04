package com.cadbbox.parser.web.api;

import com.cadbbox.parser.web.ModelService;
import com.cadbbox.parser.web.ModelService.BadFileException;
import com.cadbbox.parser.web.ModelService.ModelNotFoundException;
import com.cadbbox.parser.web.ModelService.StepParseException;
import com.cadbbox.parser.web.dto.ModelMetadata;
import com.cadbbox.parser.web.dto.TreeNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;

/** REST API for uploading and inspecting STEP models. */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelService service;

    public ModelController(ModelService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public ResponseEntity<ModelMetadata> upload(@RequestParam("file") MultipartFile file) throws IOException {
        ModelMetadata meta = service.upload(file);
        return ResponseEntity.ok(meta);
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

    // ---- error mapping (RFC 9457 problem+json) ----

    @ExceptionHandler(BadFileException.class)
    public ResponseEntity<ProblemDetail> badFile(BadFileException e) {
        return problem(HttpStatus.BAD_REQUEST, "bad-file", e.getMessage());
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
