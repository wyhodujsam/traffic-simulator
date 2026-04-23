package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.config.Osm2StreetsConfig;
import com.trafficsimulator.dto.BboxRequest;
import com.trafficsimulator.service.Osm2StreetsService.Osm2StreetsCliException;

/**
 * End-to-end tests for {@link Osm2StreetsService#fetchAndConvert(BboxRequest)} — Phase 24 Plan
 * 24-04.
 *
 * <p>Uses a test-only subclass of {@link Osm2StreetsService} that overrides
 * {@link Osm2StreetsService#executeCli(byte[])} to return canned subprocess output (JSON), and a
 * stub {@link OverpassXmlFetcher} that returns dummy XML bytes. The combination exercises the
 * full pipeline — Overpass fetch -> executeCli -> JSON parse -> StreetNetworkMapper -> MapValidator
 * -> MapConfig — without spawning the real Rust binary and without any network traffic.
 */
class Osm2StreetsServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MapValidator validator = new MapValidator();
    private final BboxRequest bbox = new BboxRequest(52.22, 21.0, 52.221, 21.001);

    private String loadFixture(String name) throws IOException {
        Path p = Paths.get("src/test/resources/osm2streets/" + name + "-streetnetwork.json");
        if (!Files.exists(p)) {
            p = Paths.get("backend/src/test/resources/osm2streets/" + name + "-streetnetwork.json");
        }
        return Files.readString(p);
    }

    /**
     * Builds a service whose executeCli always returns the supplied canned JSON (or throws the
     * supplied exception). The OverpassXmlFetcher stub returns a fixed dummy payload — neither
     * value reaches the real network.
     */
    private Osm2StreetsService serviceWithCannedStdout(String cannedJson, IOException ioToThrow) {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("/nonexistent/osm2streets-cli");
        cfg.setTimeoutSeconds(5);

        OverpassXmlFetcher stubFetcher =
                new OverpassXmlFetcher(null, java.util.List.of()) {
                    @Override
                    public byte[] fetchXmlBytes(BboxRequest b) {
                        return "<osm/>".getBytes(StandardCharsets.UTF_8);
                    }
                };

        return new Osm2StreetsService(cfg, stubFetcher, objectMapper, validator) {
            @Override
            String executeCli(byte[] osmXml) throws IOException {
                if (ioToThrow != null) {
                    throw ioToThrow;
                }
                return cannedJson;
            }
        };
    }

    private Osm2StreetsService serviceThatThrowsCliException(Osm2StreetsCliException toThrow) {
        Osm2StreetsConfig cfg = new Osm2StreetsConfig();
        cfg.setBinaryPath("/nonexistent/osm2streets-cli");
        cfg.setTimeoutSeconds(5);

        OverpassXmlFetcher stubFetcher =
                new OverpassXmlFetcher(null, java.util.List.of()) {
                    @Override
                    public byte[] fetchXmlBytes(BboxRequest b) {
                        return "<osm/>".getBytes(StandardCharsets.UTF_8);
                    }
                };

        return new Osm2StreetsService(cfg, stubFetcher, objectMapper, validator) {
            @Override
            String executeCli(byte[] osmXml) {
                throw toThrow;
            }
        };
    }

    // ------------------------------------------------------------------
    // S1 — success path (straight fixture) returns validator-clean MapConfig
    // ------------------------------------------------------------------

    @Test
    void s1_success_straightFixtureProducesValidatorCleanMapConfig() throws IOException {
        String cannedJson = loadFixture("straight");
        Osm2StreetsService svc = serviceWithCannedStdout(cannedJson, null);

        MapConfig cfg = svc.fetchAndConvert(bbox);

        assertThat(cfg).isNotNull();
        assertThat(cfg.getRoads()).isNotEmpty();
        assertThat(cfg.getNodes()).isNotEmpty();
        assertThat(validator.validate(cfg)).isEmpty();
        assertThat(cfg.getRoads()).allSatisfy(r -> assertThat(r.getId()).startsWith("o2s-"));
    }

    // ------------------------------------------------------------------
    // S2 — empty roads/intersections -> IllegalStateException from mapper propagates
    // ------------------------------------------------------------------

    @Test
    void s2_empty_throwsIllegalStateException() {
        String emptyJson = "{\"roads\":[],\"intersections\":[]}";
        Osm2StreetsService svc = serviceWithCannedStdout(emptyJson, null);

        assertThatThrownBy(() -> svc.fetchAndConvert(bbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads");
    }

    // ------------------------------------------------------------------
    // S3 — executeCli throws Osm2StreetsCliException -> propagates to caller
    // ------------------------------------------------------------------

    @Test
    void s3_subprocessFailure_propagates() {
        Osm2StreetsService svc =
                serviceThatThrowsCliException(
                        new Osm2StreetsCliException("osm2streets-cli exited 2: boom"));

        assertThatThrownBy(() -> svc.fetchAndConvert(bbox))
                .isInstanceOf(Osm2StreetsCliException.class)
                .hasMessageContaining("exited 2");
    }

    // ------------------------------------------------------------------
    // S4 — invalid JSON from subprocess -> Osm2StreetsCliException with invalid-json marker
    // ------------------------------------------------------------------

    @Test
    void s4_invalidJson_wrappedInCliException() {
        Osm2StreetsService svc = serviceWithCannedStdout("not json at all", null);

        assertThatThrownBy(() -> svc.fetchAndConvert(bbox))
                .isInstanceOf(Osm2StreetsCliException.class)
                .hasMessageContaining("invalid JSON");
    }

    // ------------------------------------------------------------------
    // S5 — isAvailable gates controller; service itself still attempts when binary missing.
    //       This test documents the contract: fetchAndConvert does NOT check isAvailable internally;
    //       that is the controller's responsibility (Plan 24-05). When the Overpass fetcher throws,
    //       that exception propagates normally.
    // ------------------------------------------------------------------

    @Test
    void s5_isAvailable_falseDoesNotShortCircuitFetchAndConvert() throws IOException {
        // isAvailable returns false because binaryPath is /nonexistent — but fetchAndConvert still
        // proceeds (it's the controller's job to guard). We exercise the contract by overriding
        // executeCli to return empty; the pipeline reaches StreetNetworkMapper and throws "No roads".
        Osm2StreetsService svc = serviceWithCannedStdout("{\"roads\":[],\"intersections\":[]}", null);

        assertThat(svc.isAvailable())
                .as("isAvailable is false (binaryPath does not exist)")
                .isFalse();

        // fetchAndConvert still executes — the controller guard belongs in Plan 24-05.
        assertThatThrownBy(() -> svc.fetchAndConvert(bbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads");
    }
}
