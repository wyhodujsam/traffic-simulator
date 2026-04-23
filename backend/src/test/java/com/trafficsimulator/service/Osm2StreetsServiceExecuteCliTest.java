package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.config.Osm2StreetsConfig;
import com.trafficsimulator.service.Osm2StreetsService.Osm2StreetsCliException;
import com.trafficsimulator.service.Osm2StreetsService.Osm2StreetsCliTimeoutException;

/**
 * Unit tests for {@link Osm2StreetsService#executeCli(byte[])} mechanics (Phase 24 Plan 24-03).
 *
 * <p>Uses shell-script stand-in binaries so CI does not depend on the real osm2streets-cli. Each
 * test constructs a fresh {@link Osm2StreetsConfig} pointed at a script staged under the JUnit
 * {@link TempDir}, wires it into a plain (non-Spring) {@code Osm2StreetsService} instance, and
 * exercises one facet of the subprocess contract:
 *
 * <ul>
 *   <li>A — success path (exit 0 → stdout returned)
 *   <li>B — non-zero exit translates to {@link Osm2StreetsCliException} carrying stderr
 *   <li>C — hard timeout translates to {@link Osm2StreetsCliTimeoutException}
 *   <li>D — 2 MB stdin completes without deadlock (separate stdout/stderr drain threads — RESEARCH Pitfall 6)
 *   <li>E — {@code isAvailable()} returns false when the binary is missing
 *   <li>F — {@code isAvailable()} returns true when the real Plan 24-01 binary is present
 *   <li>G — {@code converterName()} returns {@code "osm2streets"}
 *   <li>H — {@code fetchAndConvert} throws {@link UnsupportedOperationException} (wiring lands in Plan 24-04)
 * </ul>
 */
class Osm2StreetsServiceExecuteCliTest {

    @TempDir Path tempDir;

    private Path successScript;
    private Path failScript;
    private Path slowScript;

    @BeforeEach
    void setUp() throws IOException {
        successScript = writeExecutableScript(
                "success.sh",
                "#!/bin/sh\nexec cat\n");
        failScript = writeExecutableScript(
                "fail.sh",
                "#!/bin/sh\ncat >/dev/null\necho boom >&2\nexit 2\n");
        slowScript = writeExecutableScript(
                "slow.sh",
                "#!/bin/sh\nsleep 5\necho late\n");
    }

    // ------------------------------------------------------------------
    // A — success path
    // ------------------------------------------------------------------

    @Test
    void executeCli_success_returnsStdoutAsUtf8() throws IOException {
        Osm2StreetsService svc = serviceFor(successScript, 10);

        String output = svc.executeCli("<osm/>".getBytes(StandardCharsets.UTF_8));

        assertThat(output).isEqualTo("<osm/>");
    }

    // ------------------------------------------------------------------
    // B — non-zero exit
    // ------------------------------------------------------------------

    @Test
    void executeCli_nonZeroExit_throwsWithStderrAndExitCode() {
        Osm2StreetsService svc = serviceFor(failScript, 10);

        assertThatThrownBy(() -> svc.executeCli("<osm/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Osm2StreetsCliException.class)
                .hasMessageContaining("exit")
                .hasMessageContaining("2")
                .hasMessageContaining("boom");
    }

    // ------------------------------------------------------------------
    // C — hard timeout
    // ------------------------------------------------------------------

    @Test
    void executeCli_timeout_throwsTimeoutException() {
        Osm2StreetsService svc = serviceFor(slowScript, 1);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> svc.executeCli(new byte[0]))
                .isInstanceOf(Osm2StreetsCliTimeoutException.class)
                .hasMessageContaining("1");
        long elapsed = System.currentTimeMillis() - start;

        // Script sleeps for 5 s; timeout is 1 s. Elapsed must be well under 5 s to prove
        // destroyForcibly() fired — loose upper bound to tolerate CI jitter.
        assertThat(elapsed).isLessThan(4000L);
    }

    // ------------------------------------------------------------------
    // D — large stdin, no pipe-buffer deadlock
    // ------------------------------------------------------------------

    @Test
    void executeCli_largeInput_noDeadlock() throws IOException {
        Osm2StreetsService svc = serviceFor(successScript, 10);
        byte[] big = new byte[2 * 1024 * 1024];
        Arrays.fill(big, (byte) 'x');

        long start = System.currentTimeMillis();
        String output = svc.executeCli(big);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output).hasSize(big.length);
        // 2 MB through `cat` should complete in well under the 10 s timeout — loose 5 s bound.
        assertThat(elapsed).isLessThan(5000L);
    }

    // ------------------------------------------------------------------
    // E — isAvailable when binary missing
    // ------------------------------------------------------------------

    @Test
    void isAvailable_returnsFalseWhenBinaryAbsent() {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("/nonexistent/path/to/osm2streets-cli");
        cfg.setTimeoutSeconds(10);
        Osm2StreetsService svc = newServiceWith(cfg);

        assertThat(svc.isAvailable()).isFalse();
    }

    // ------------------------------------------------------------------
    // F — isAvailable with real binary from Plan 24-01
    // ------------------------------------------------------------------

    @Test
    void isAvailable_returnsTrueWhenExecutable() {
        Path realBinary = Paths.get("bin/osm2streets-cli-linux-x64");
        if (!Files.isExecutable(realBinary)) {
            // Fall back to repo-root relative path if running from repo root instead of backend/.
            realBinary = Paths.get("backend/bin/osm2streets-cli-linux-x64");
        }
        Assumptions.assumeTrue(Files.isExecutable(realBinary),
                "Skipping: real osm2streets-cli binary not present (Plan 24-01 artefact)");

        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath(realBinary.toString());
        cfg.setTimeoutSeconds(10);
        Osm2StreetsService svc = newServiceWith(cfg);

        assertThat(svc.isAvailable()).isTrue();
    }

    // ------------------------------------------------------------------
    // G — converterName contract
    // ------------------------------------------------------------------

    @Test
    void converterName_returnsOsm2streets() {
        Osm2StreetsService svc = serviceFor(successScript, 10);

        assertThat(svc.converterName()).isEqualTo("osm2streets");
    }

    // ------------------------------------------------------------------
    // H — fetchAndConvert end-to-end smoke: after Plan 24-04 wiring, invoking with a shell
    //     stand-in that emits empty JSON {roads:[],intersections:[]} funnels through
    //     StreetNetworkMapper and surfaces the documented "No roads" IllegalStateException
    //     rather than the previous UnsupportedOperationException placeholder.
    // ------------------------------------------------------------------

    @Test
    void fetchAndConvert_emptyPipelineSurfacesNoRoadsError(@TempDir Path scriptDir) throws IOException {
        Path emptyJsonScript = scriptDir.resolve("empty.sh");
        Files.writeString(
                emptyJsonScript,
                "#!/bin/sh\ncat >/dev/null\necho '{\"roads\":[],\"intersections\":[],\"gps_bounds\":{\"min_lon\":21.0,\"min_lat\":52.22,\"max_lon\":21.001,\"max_lat\":52.221}}'\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                emptyJsonScript, PosixFilePermissions.fromString("rwxr-xr-x"));

        // Build a service where executeCli is spied via a subclass (script is arbitrary — we
        // override executeCli directly to keep the smoke test self-contained and avoid needing
        // the real Overpass mirrors list).
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath(emptyJsonScript.toString());
        cfg.setTimeoutSeconds(5);

        OverpassXmlFetcher stubFetcher =
                new OverpassXmlFetcher(RestClient.create(), List.of()) {
                    @Override
                    public byte[] fetchXmlBytes(com.trafficsimulator.dto.BboxRequest b) {
                        return "<osm/>".getBytes(StandardCharsets.UTF_8);
                    }
                };

        Osm2StreetsService svc =
                new Osm2StreetsService(cfg, stubFetcher, new ObjectMapper(), new MapValidator()) {
                    @Override
                    String executeCli(byte[] osmXml) {
                        return "{\"roads\":[],\"intersections\":[],\"gps_bounds\":{\"min_lon\":21.0,\"min_lat\":52.22,\"max_lon\":21.001,\"max_lat\":52.221}}";
                    }
                };

        assertThatThrownBy(
                        () ->
                                svc.fetchAndConvert(
                                        new com.trafficsimulator.dto.BboxRequest(52.22, 21.0, 52.221, 21.001)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Osm2StreetsService serviceFor(Path binary, int timeoutSeconds) {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath(binary.toString());
        cfg.setTimeoutSeconds(timeoutSeconds);
        return newServiceWith(cfg);
    }

    /** Assembles a service with all Plan 24-04 dependencies wired to lightweight stand-ins. */
    private Osm2StreetsService newServiceWith(Osm2StreetsConfig cfg) {
        OverpassXmlFetcher fetcher = new OverpassXmlFetcher(RestClient.create(), List.of());
        return new Osm2StreetsService(cfg, fetcher, new ObjectMapper(), new MapValidator());
    }

    private Path writeExecutableScript(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(script, perms);
        return script;
    }
}
