package com.cadbbox.parser.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent application settings. Stored as a JSON file at
 * <software-dir>/config.json so it survives restarts.
 *
 * <p>Currently manages:
 * <ul>
 *   <li>cacheDir — absolute path to the cache directory (uploads, bbox JSON,
 *       GLB meshes, .port file). Default: ../cache relative to the software
 *       root (sibling of the Software/ folder).</li>
 *   <li>pythonExe — path to the conda env's python.exe. Auto-detected if empty.</li>
 * </ul>
 */
@Component
public class AppSettings {

    private static final Path CONFIG_FILE = findConfigFile();

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> settings = new ConcurrentHashMap<>();

    public AppSettings() {
        load();
        // Set defaults if missing.
        if (!settings.containsKey("cacheDir")) {
            settings.put("cacheDir", defaultCacheDir());
        }
    }

    public String getCacheDir() {
        return settings.getOrDefault("cacheDir", defaultCacheDir());
    }

    public void setCacheDir(String dir) {
        settings.put("cacheDir", dir);
        save();
    }

    public String getPythonExe() {
        return settings.getOrDefault("pythonExe", "");
    }

    public void setPythonExe(String exe) {
        settings.put("pythonExe", exe);
        save();
    }

    public Map<String, String> getAll() {
        return new java.util.TreeMap<>(settings);
    }

    public void updateAll(Map<String, String> updates) {
        settings.putAll(updates);
        save();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                Map<String, String> loaded = mapper.readValue(CONFIG_FILE.toFile(), Map.class);
                settings.putAll(loaded);
            } catch (Exception ignored) { }
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), settings);
        } catch (IOException e) {
            System.err.println("[settings] failed to save config: " + e.getMessage());
        }
    }

    /** Default cache dir: .cache under the working directory (Software/). */
    private static String defaultCacheDir() {
        String cwd = System.getProperty("user.dir");
        return Paths.get(cwd, ".cache").toAbsolutePath().toString().replace('\\', '/');
    }

    /** Config file lives at Software/config.json (sibling of backend/). */
    private static Path findConfigFile() {
        String cwd = System.getProperty("user.dir");
        Path softwareRoot = Paths.get(cwd).getParent();
        if (softwareRoot != null) {
            return softwareRoot.resolve("config.json");
        }
        return Paths.get("config.json");
    }

    public Path getConfigFilePath() {
        return CONFIG_FILE;
    }
}
