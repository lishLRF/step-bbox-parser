package com.cadbbox.parser.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Generates a tessellated glTF/GLB mesh from an uploaded STEP file by spawning
 * the Python `step_to_mesh.py` script (cadquery + trimesh) under the dedicated
 * conda env.
 *
 * <p>The mesh is generated on first request for a model id and cached on disk;
 * subsequent requests stream the cached GLB. Generation is synchronous (the
 * first call is slow — minutes for a 173 MB file — because OCCT tessellation is
 * heavy). For very large files the caller should set a long client timeout.
 */
@Service
public class MeshService {

    private final Path meshCacheDir;
    /** Python interpreter + script path. Configured for the dedicated conda env. */
    private final String pythonExe;
    private final Path scriptPath;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public MeshService(@Value("${parser.work-dir:#{T(java.lang.System).getProperty('java.io.tmpdir')} }") String workDir,
                       @Value("${mesh.python-exe:python}") String pythonExe,
                       @Value("${mesh.script:scripts/step_to_mesh.py}") String script) {
        this.meshCacheDir = Paths.get(workDir, "step-bbox-meshes");
        this.pythonExe = pythonExe;
        this.scriptPath = Paths.get(script);
        try { Files.createDirectories(meshCacheDir); } catch (IOException ignored) { }
    }

    /** Generate (or return cached) GLB bytes for the given model. */
    public byte[] getOrGenerate(String modelId, Path sourceStep) throws IOException, InterruptedException {
        Path glb = meshCacheDir.resolve(modelId + ".glb");
        Object lock = locks.computeIfAbsent(modelId, k -> new Object());
        synchronized (lock) {
            if (Files.exists(glb) && Files.size(glb) > 0) {
                return Files.readAllBytes(glb);
            }
            runConverter(sourceStep, glb);
            return Files.readAllBytes(glb);
        }
    }

    /** Drop a cached mesh (called when a model is deleted). */
    public void evict(String modelId) {
        try { Files.deleteIfExists(meshCacheDir.resolve(modelId + ".glb")); } catch (IOException ignored) { }
        locks.remove(modelId);
    }

    private void runConverter(Path step, Path outGlb) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                pythonExe,
                scriptPath.toAbsolutePath().toString(),
                step.toAbsolutePath().toString(),
                outGlb.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Stream the converter's stdout/stderr so we can diagnose failures.
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.append(line).append('\n');
        }
        boolean finished = p.waitFor(30, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Mesh generation timed out after 30 minutes.\n" + log);
        }
        if (p.exitValue() != 0) {
            throw new IOException("Mesh generation failed (exit " + p.exitValue() + ").\n" + log);
        }
        if (!Files.exists(outGlb) || Files.size(outGlb) == 0) {
            throw new IOException("Mesh generation produced no output.\n" + log);
        }
    }
}
