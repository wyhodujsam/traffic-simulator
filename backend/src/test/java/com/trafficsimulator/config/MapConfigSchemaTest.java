package com.trafficsimulator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Schema tests for Plan 25-03 — verify three optional MapConfig fields (seed, perturbation,
 * initialVehicles) deserialise correctly and existing scenarios remain backwards compatible
 * (DET-04 regression).
 */
class MapConfigSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schemaAcceptsSeed() throws Exception {
        String json = "{\"id\":\"x\",\"name\":\"X\",\"seed\":999,\"nodes\":[],\"roads\":[],"
                + "\"intersections\":[],\"spawnPoints\":[],\"despawnPoints\":[],"
                + "\"defaultSpawnRate\":0.0}";
        MapConfig cfg = mapper.readValue(json, MapConfig.class);
        assertThat(cfg.getSeed()).isEqualTo(999L);
    }

    @Test
    void schemaAcceptsNullSeed() throws Exception {
        String json = "{\"id\":\"x\",\"name\":\"X\",\"nodes\":[],\"roads\":[],"
                + "\"intersections\":[],\"spawnPoints\":[],\"despawnPoints\":[],"
                + "\"defaultSpawnRate\":0.0}";
        MapConfig cfg = mapper.readValue(json, MapConfig.class);
        assertThat(cfg.getSeed()).isNull();
    }

    @Test
    void schemaAcceptsPerturbation() throws Exception {
        String json = "{\"id\":\"x\",\"name\":\"X\",\"perturbation\":{\"tick\":200,"
                + "\"vehicleIndex\":0,\"targetSpeed\":5.0,\"durationTicks\":60},"
                + "\"nodes\":[],\"roads\":[],\"intersections\":[],\"spawnPoints\":[],"
                + "\"despawnPoints\":[],\"defaultSpawnRate\":0.0}";
        MapConfig cfg = mapper.readValue(json, MapConfig.class);
        assertThat(cfg.getPerturbation()).isNotNull();
        assertThat(cfg.getPerturbation().getTick()).isEqualTo(200L);
        assertThat(cfg.getPerturbation().getVehicleIndex()).isZero();
        assertThat(cfg.getPerturbation().getTargetSpeed()).isEqualTo(5.0);
        assertThat(cfg.getPerturbation().getDurationTicks()).isEqualTo(60L);
    }

    @Test
    void schemaAcceptsInitialVehicles() throws Exception {
        String json = "{\"id\":\"x\",\"name\":\"X\",\"initialVehicles\":["
                + "{\"roadId\":\"r0\",\"laneIndex\":0,\"position\":0.0,\"speed\":15.0},"
                + "{\"roadId\":\"r1\",\"laneIndex\":1,\"position\":10.0,\"speed\":20.0}],"
                + "\"nodes\":[],\"roads\":[],\"intersections\":[],\"spawnPoints\":[],"
                + "\"despawnPoints\":[],\"defaultSpawnRate\":0.0}";
        MapConfig cfg = mapper.readValue(json, MapConfig.class);
        assertThat(cfg.getInitialVehicles()).hasSize(2);
        assertThat(cfg.getInitialVehicles().get(0).getRoadId()).isEqualTo("r0");
        assertThat(cfg.getInitialVehicles().get(0).getLaneIndex()).isZero();
        assertThat(cfg.getInitialVehicles().get(0).getPosition()).isEqualTo(0.0);
        assertThat(cfg.getInitialVehicles().get(0).getSpeed()).isEqualTo(15.0);
        assertThat(cfg.getInitialVehicles().get(1).getRoadId()).isEqualTo("r1");
        assertThat(cfg.getInitialVehicles().get(1).getLaneIndex()).isEqualTo(1);
    }

    @Test
    void existingScenariosStillLoad() throws Exception {
        // DET-04 regression: every shipped scenario must still parse with the schema extension.
        List<String> scenarios = List.of(
                "maps/combined-loop.json",
                "maps/four-way-signal.json",
                "maps/highway-merge.json",
                "maps/phantom-jam-corridor.json",
                "maps/roundabout.json",
                "maps/straight-road.json",
                "maps/construction-zone.json");
        for (String path : scenarios) {
            try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
                assertThat(is).as("classpath resource " + path).isNotNull();
                MapConfig cfg = mapper.readValue(is, MapConfig.class);
                assertThat(cfg.getId()).as("scenario id present in " + path).isNotNull();
            }
        }
    }
}
