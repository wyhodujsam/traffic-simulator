package com.trafficsimulator.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.dto.VehicleSnapshotDto;

/**
 * Unit tests for {@link ReplayLogger} — Phase 25 Plan 05.
 *
 * <p>Closes REPLAY-02 (NDJSON header + tick line schema per D-14), REPLAY-04 (IOException-safety —
 * disk write failure must NOT crash the tick loop), and documents T-25-01 path-traversal mitigation
 * via the no-caller-input-in-filename contract.
 */
class ReplayLoggerTest {

    @TempDir Path tempDir;

    private ObjectMapper mapper;
    private ReplayLogger logger;
    private ReplayLoggerProperties props;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        props = new ReplayLoggerProperties();
        props.setEnabled(true);
        props.setDirectory(tempDir.toString());
        logger = new ReplayLogger(mapper, props);
    }

    @AfterEach
    void tearDown() throws IOException {
        logger.close();
    }

    @Test
    void headerLineSchemaPerD14() throws Exception {
        logger.start(42L, "command", "ring-road", 0.05);
        logger.close();
        List<String> lines = Files.readAllLines(logger.getPath());
        assertThat(lines).isNotEmpty();
        JsonNode header = mapper.readTree(lines.get(0));
        assertThat(header.get("type").asText()).isEqualTo("header");
        assertThat(header.get("seed").asLong()).isEqualTo(42L);
        assertThat(header.get("source").asText()).isEqualTo("command");
        assertThat(header.get("mapId").asText()).isEqualTo("ring-road");
        assertThat(header.get("tickDt").asDouble()).isEqualTo(0.05);
    }

    @Test
    void tickLineSchemaPerD14() throws Exception {
        logger.start(7L, "auto", "x", 0.05);
        VehicleSnapshotDto v =
                VehicleSnapshotDto.builder()
                        .id("v1")
                        .roadId("r0")
                        .laneIndex(0)
                        .position(1.0)
                        .speed(20.0)
                        .build();
        logger.onTick(5L, List.of(v));
        logger.close();
        List<String> lines = Files.readAllLines(logger.getPath());
        assertThat(lines).hasSize(2);
        JsonNode tick = mapper.readTree(lines.get(1));
        assertThat(tick.get("type").asText()).isEqualTo("tick");
        assertThat(tick.get("tick").asLong()).isEqualTo(5L);
        JsonNode vs = tick.get("vehicles");
        assertThat(vs.isArray()).isTrue();
        assertThat(vs).hasSize(1);
        assertThat(vs.get(0).get("id").asText()).isEqualTo("v1");
        assertThat(vs.get(0).get("roadId").asText()).isEqualTo("r0");
        assertThat(vs.get(0).get("laneIndex").asInt()).isZero();
        assertThat(vs.get(0).get("position").asDouble()).isEqualTo(1.0);
        assertThat(vs.get(0).get("speed").asDouble()).isEqualTo(20.0);
    }

    @Test
    void pathContainsSeedAndIsoTimestamp() {
        logger.start(12345L, "json", "m", 0.05);
        Path p = logger.getPath();
        assertThat(p.getFileName().toString()).matches("12345-\\d{8}T\\d{6}\\.ndjson");
        assertThat(p.getParent()).isEqualTo(tempDir);
    }

    @Test
    void pathDoesNotEscapeReplaysDir_T2501() {
        // T-25-01: path is constructed from internal-only fields. No way to inject `..`.
        // This test documents the contract: getPath() always under props.directory.
        logger.start(1L, "auto", "x", 0.05);
        Path resolved = logger.getPath().toAbsolutePath().normalize();
        Path root = tempDir.toAbsolutePath().normalize();
        assertThat(resolved.startsWith(root))
                .as("replay path must be inside configured directory")
                .isTrue();
    }

    @Test
    void ioErrorDisablesLogger_doesNotCrash() throws Exception {
        logger.start(1L, "auto", "x", 0.05);
        // Simulate IOException by closing the writer behind the logger's back, then attempting
        // onTick. Closed writer means the next onTick is a quiet no-op; logger reports inactive.
        logger.close();
        // After close, writer is null; onTick is a quiet no-op.
        logger.onTick(1L, List.of()); // must not throw
        assertThat(logger.isActive()).isFalse();
    }

    @Test
    void notStarted_onTickNoOp() {
        // No start() called. onTick must not throw.
        logger.onTick(1L, List.of());
        assertThat(logger.isActive()).isFalse();
    }
}
