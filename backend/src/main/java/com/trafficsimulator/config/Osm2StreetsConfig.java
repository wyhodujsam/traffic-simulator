package com.trafficsimulator.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the osm2streets Rust binary subprocess (Phase 24).
 *
 * <p>Mirrors the shape of {@link ClaudeCliConfig}: a binary path on disk, a hard invocation
 * timeout, and a working directory for transient artefacts. The binary itself is built and
 * provenanced by Phase 24 Plan 24-01 ({@code backend/bin/osm2streets-cli-linux-x64}); this bean is
 * the integration seam that {@code Osm2StreetsService} reads from so the path and timeout can be
 * overridden in tests without touching the service code.
 *
 * <p>Phase 24.2 Plan 01: {@link #getBinaryPath()} now smart-resolves the configured path against
 * the JVM working directory so the production default works regardless of whether the backend is
 * started from {@code project-root} or from {@code backend/}. See the Javadoc on
 * {@link #getBinaryPath()} for the algorithm; see {@code 24.2-CONTEXT.md} and {@code 24.2-01-PLAN.md}
 * for the rationale (notably the {@code .git}-only project-root marker decision).
 */
@Component
@ConfigurationProperties(prefix = "osm2streets")
public class Osm2StreetsConfig {

    /** Path to the osm2streets-cli binary. Defaults to {@code backend/bin/osm2streets-cli-linux-x64}. */
    private String binaryPath = "backend/bin/osm2streets-cli-linux-x64";

    /** Hard timeout in seconds for a single osm2streets-cli invocation (24-CONTEXT D10). */
    private int timeoutSeconds = 30;

    /** Directory for temp files (reserved for future use by the mapper in Plan 24-04). */
    private String tempDir = System.getProperty("java.io.tmpdir");

    /**
     * Returns the path to the osm2streets-cli binary, smart-resolved against the current working
     * directory (Phase 24.2 Plan 01).
     *
     * <p>Resolution algorithm (24.2-CONTEXT.md §"Decision space" option (a), with project-root
     * marker = {@code .git} only — see Plan 01 §"Project-root marker decision" for why combined
     * {@code .git}+{@code pom.xml} was rejected against this repo's layout):
     *
     * <ol>
     *   <li>If {@code binaryPath} is absolute → return it unchanged. (Backwards-compat AC#3 —
     *       Phase 24.1 absolute-path overrides.)
     *   <li>If {@code Path.of(user.dir).resolve(binaryPath)} exists as a regular file → return
     *       {@code binaryPath} unchanged. (Backwards-compat AC#4 — the OsmPipelineSmokeIT
     *       cwd-relative override style; production-style invocations from project root.)
     *   <li>Walk up from {@code user.dir} looking for the first ancestor directory that contains a
     *       {@code .git} entry (file OR directory — supports git-worktrees, where {@code .git} is a
     *       regular file). If found AND {@code projectRoot.resolve(binaryPath)} exists as a regular
     *       file → return that absolute path as a String. (AC#1 — fixes the
     *       {@code cd backend && mvn spring-boot:run} dev flow.)
     *   <li>Otherwise → return {@code binaryPath} unchanged. The caller's {@link ProcessBuilder}
     *       will then surface a clean ENOENT-style error rather than the resolver hiding the
     *       failure with a phantom path.
     * </ol>
     *
     * <p>The resolver runs INSIDE the getter so {@code Osm2StreetsService.executeCli} and
     * {@code Osm2StreetsService.isAvailable} see the resolved value transparently — no service-side
     * changes required.
     */
    public String getBinaryPath() {
        if (binaryPath == null) {
            return null;
        }
        Path raw = Paths.get(binaryPath);
        if (raw.isAbsolute()) {
            return binaryPath;
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        if (Files.isRegularFile(cwd.resolve(raw))) {
            return binaryPath;
        }
        Path projectRoot = findProjectRoot(cwd);
        if (projectRoot != null) {
            Path resolved = projectRoot.resolve(raw);
            if (Files.isRegularFile(resolved)) {
                return resolved.toString();
            }
        }
        return binaryPath;
    }

    /** Test seam: returns the as-configured path, bypassing resolution. */
    String getRawBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }

    /**
     * Walks up from {@code start} returning the first ancestor that contains a {@code .git} entry
     * (file OR directory — supports git-worktrees, which use a regular file at {@code .git}, not a
     * directory). Returns {@code null} if no ancestor with {@code .git} exists.
     *
     * <p>Marker rationale: this repo has {@code .git} at exactly one level (the project root) and
     * has no submodules / no nested {@code .git} directories — so a {@code .git}-only marker has
     * zero false-positive risk here. A combined {@code .git}+{@code pom.xml} marker would have
     * failed against the real layout because there is no top-level {@code pom.xml} (the only
     * {@code pom.xml} is in {@code backend/}).
     */
    private static Path findProjectRoot(Path start) {
        Path cur = start.toAbsolutePath().normalize();
        while (cur != null) {
            if (Files.exists(cur.resolve(".git"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }
}
