package com.cadbbox.parser.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;

/**
 * Captures the actually-assigned HTTP port (since {@code server.port=0} asks the
 * OS for any free port) and advertises it so the frontend dev server / launcher
 * can find the backend without a hardcoded port.
 *
 * <p>Writes {@code <work-dir>/step-bbox-parser/.port} containing a single line
 * {@code port pid}. On startup it first clears any stale {@code .port} files
 * whose recorded PID is no longer alive — so stopping the app frees the slot
 * and a next launch overwrites cleanly.
 */
@Component
public class PortPublisher {

    private final Path portFile;

    public PortPublisher(@Value("${parser.work-dir:#{T(java.lang.System).getProperty('java.io.tmpdir')} }") String workDir) {
        // work-dir is already <tmpdir>/step-bbox-parser/work (see application.yml);
        // the .port file sits directly inside it, no extra nesting.
        this.portFile = Paths.get(workDir, ".port");
    }

    @EventListener
    public void onReady(ApplicationReadyEvent e) {
        // Clean up stale .port files from previous runs that didn't exit cleanly.
        clearStale();
    }

    @EventListener
    public void onServerReady(WebServerInitializedEvent e) {
        int port = e.getWebServer().getPort();
        long pid = currentPid();
        try {
            Files.createDirectories(portFile.getParent());
            Files.writeString(portFile, port + " " + pid + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[step-bbox-parser] HTTP port " + port
                    + " advertised at " + portFile + " (pid=" + pid + ")");
        } catch (IOException ex) {
            System.err.println("[step-bbox-parser] could not write .port file: " + ex.getMessage());
        }
    }

    /** Remove the .port file if it points at us (or is stale). */
    private void clearStale() {
        try {
            if (Files.exists(portFile)) {
                String[] parts = Files.readString(portFile).trim().split("\\s+");
                if (parts.length >= 2) {
                    long recordedPid = Long.parseLong(parts[1]);
                    if (!isAlive(recordedPid)) {
                        Files.deleteIfExists(portFile);
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) { }
    }

    private static boolean isAlive(long pid) {
        try {
            // OS-native process handle (JDK 9+). Returns false if the pid is gone.
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Throwable t) {
            return true; // be conservative — don't delete if we can't tell
        }
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** On graceful shutdown, remove the .port file so the slot is visibly free. */
    @PreDestroy
    public void cleanup() {
        try { Files.deleteIfExists(portFile); } catch (IOException ignored) { }
    }
}
