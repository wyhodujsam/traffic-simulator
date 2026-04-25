package com.trafficsimulator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Osm2StreetsConfig#getBinaryPath()} smart resolution
 * (Phase 24.2 Plan 01). Closes the working-directory gap that caused
 * {@code POST /api/osm/fetch-roads-o2s} to return 503 when the backend started
 * with {@code cwd=backend/} — see {@code 24.2-CONTEXT.md "Bug summary"}.
 *
 * <p>Fixture mirrors the real repo layout: {@code .git} directory at the project
 * root, NO {@code pom.xml} at the project root (the only {@code pom.xml} is in
 * {@code backend/}). This is critical: a combined {@code .git}+{@code pom.xml}
 * marker algorithm would NOT find a project root in this fixture (or in the real
 * repo) and Test 3 would falsely pass against unfixed code.
 *
 * <p>No Mockito: {@link Osm2StreetsConfig} is a value bean
 * ({@code mockito-anti-patterns.md} Anti-Pattern 2). Filesystem fixtures via
 * {@link TempDir}; cwd controlled deterministically via the {@code user.dir}
 * system property (which the resolver under test reads via
 * {@link System#getProperty(String)}).
 */
class Osm2StreetsConfigTest {

    @TempDir Path tempDir;
    private Path backendDir;
    private Path realBinary;

    @BeforeEach
    void setUp() throws IOException {
        // Project-root marker: .git directory ONLY at the tempDir root.
        // Mirrors the REAL repo layout — /home/sebastian/apps/traffic-simulator/.git
        // exists, /home/sebastian/apps/traffic-simulator/pom.xml DOES NOT exist.
        Files.createDirectory(tempDir.resolve(".git"));

        // backend/ is the only Maven module — its pom.xml is a DISTRACTOR for any
        // algorithm that would (incorrectly) use pom.xml as a project-root marker.
        backendDir = tempDir.resolve("backend");
        Files.createDirectories(backendDir.resolve("bin"));
        Files.createFile(backendDir.resolve("pom.xml"));

        realBinary = backendDir.resolve("bin/osm2streets-cli-linux-x64");
        Files.writeString(realBinary, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(
                realBinary, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * Test 1: AC#3 backwards-compat — absolute paths bypass the resolver
     * unchanged, regardless of {@code user.dir}.
     */
    @Test
    void getBinaryPath_absolutePath_returnedUnchanged() {
        String absolute = realBinary.toAbsolutePath().toString();
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath(absolute);

        runWithUserDir(
                tempDir,
                () ->
                        assertThat(cfg.getBinaryPath())
                                .as("absolute paths must be returned unchanged (AC#3)")
                                .isEqualTo(absolute));
    }

    /**
     * Test 2: AC#4 backwards-compat — when the configured path is cwd-relative
     * AND already resolves to an existing file from the current {@code user.dir},
     * the resolver returns it unchanged. Mirrors the Phase 24.1 OsmPipelineSmokeIT
     * override style ({@code osm2streets.binary-path=bin/osm2streets-cli-linux-x64}
     * with {@code cwd=backend/}).
     */
    @Test
    void getBinaryPath_cwdRelative_existsFromBackendCwd_returnedUnchanged() {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("bin/osm2streets-cli-linux-x64");

        runWithUserDir(
                backendDir,
                () ->
                        assertThat(cfg.getBinaryPath())
                                .as(
                                        "cwd-relative path that exists from cwd must be returned"
                                                + " unchanged (AC#4 — Phase 24.1 override style)")
                                .isEqualTo("bin/osm2streets-cli-linux-x64"));
    }

    /**
     * Test 3 — LOAD-BEARING RED: AC#1 — the production default
     * {@code backend/bin/osm2streets-cli-linux-x64} (root-relative) must resolve
     * correctly when the JVM cwd is {@code backend/} (the documented
     * {@code cd backend && mvn spring-boot:run} dev flow).
     *
     * <p>Today's broken behavior: getter returns the unchanged
     * {@code "backend/bin/..."} which {@link ProcessBuilder} resolves against
     * {@code backend/} → {@code backend/backend/bin/...} → ENOENT → HTTP 503.
     * After the fix, the resolver walks up from {@code backend/}, finds
     * {@code .git} at {@code tempDir}, and rewrites the path to the absolute
     * location of the actual binary.
     *
     * <p>This is the only test that demands resolution logic; Tests 1, 2, 4, 5
     * happen to pass against the unfixed plain-getter because they assert
     * behaviors the unchanged getter satisfies.
     */
    @Test
    void getBinaryPath_rootRelative_cwdIsBackend_resolvesToProjectRoot() throws IOException {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("backend/bin/osm2streets-cli-linux-x64");

        Path expected = realBinary.toRealPath();

        runWithUserDir(
                backendDir,
                () -> {
                    String returned = cfg.getBinaryPath();
                    assertThat(returned)
                            .as(
                                    "resolver must rewrite the root-relative default path"
                                            + " when cwd is backend/ (AC#1 — the load-bearing"
                                            + " RED case)")
                            .isNotEqualTo("backend/bin/osm2streets-cli-linux-x64");
                    assertThat(Path.of(returned))
                            .as("resolved path must be absolute")
                            .isAbsolute();
                    assertThat(Path.of(returned))
                            .as(
                                    "resolved path must point to the actual binary under"
                                            + " tempDir/backend/bin")
                            .isEqualTo(expected);
                });
    }

    /**
     * Test 4: AC#2 backwards-compat — when cwd is the project root
     * ({@code mvn -pl backend spring-boot:run}) the default path already resolves
     * cwd-relatively to an existing file, so the resolver returns it unchanged.
     */
    @Test
    void getBinaryPath_rootRelative_cwdIsProjectRoot_returnedUnchanged() {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("backend/bin/osm2streets-cli-linux-x64");

        runWithUserDir(
                tempDir,
                () ->
                        assertThat(cfg.getBinaryPath())
                                .as(
                                        "from project-root cwd the default path is already"
                                                + " cwd-resolvable; resolver must NOT rewrite"
                                                + " (AC#2)")
                                .isEqualTo("backend/bin/osm2streets-cli-linux-x64"));
    }

    /**
     * Test 5: iron-law — when the binary is genuinely missing at all candidate
     * locations the resolver returns the configured path UNCHANGED, so callers
     * see a clean ENOENT-style failure rather than a misleading rewritten path.
     */
    @Test
    void getBinaryPath_missingEverywhere_returnsConfiguredPathUnchanged() {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("does/not/exist/osm2streets-cli");

        runWithUserDir(
                backendDir,
                () ->
                        assertThat(cfg.getBinaryPath())
                                .as(
                                        "missing binary must surface the configured path"
                                                + " unchanged — resolver must NOT silently"
                                                + " fabricate a phantom path")
                                .isEqualTo("does/not/exist/osm2streets-cli"));
    }

    // Test 6 added by Task 3 (real-repo smoke — does NOT use @TempDir).

    /**
     * Runs {@code body} with {@code user.dir} system property temporarily set to
     * {@code cwd}. MUST restore in {@code finally} so subsequent tests / Surefire
     * fork are clean.
     *
     * <p>NOTE: {@link System#setProperty(String, String)} on {@code "user.dir"}
     * does NOT change the actual JVM working directory — but the resolver under
     * test reads {@code user.dir} via {@link System#getProperty(String)}, so
     * swapping the property is sufficient for these unit tests.
     */
    private void runWithUserDir(Path cwd, Runnable body) {
        String original = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", cwd.toString());
            body.run();
        } finally {
            System.setProperty("user.dir", original);
        }
    }
}
