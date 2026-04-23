package com.trafficsimulator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MapValidator}.
 *
 * <p>Plan 24-02 added an OPTIONAL {@link MapConfig.RoadConfig#getLanes()} field that carries
 * per-lane metadata from osm2streets. The validator intentionally ignores the field — it only
 * validates {@code laneCount}, {@code length}, and {@code speedLimit}. This test asserts that a
 * MapConfig with a populated {@code lanes[]} still passes validation with zero errors.
 */
class MapValidatorTest {

    private final MapValidator mapValidator = new MapValidator();

    /** Build a minimal, validator-clean MapConfig: 2 nodes (ENTRY + EXIT) + 1 bidirectional road. */
    private MapConfig buildMinimalMapConfig() {
        MapConfig cfg = new MapConfig();
        cfg.setId("test-lanes");

        MapConfig.NodeConfig entry = new MapConfig.NodeConfig();
        entry.setId("n1");
        entry.setType("ENTRY");
        entry.setX(0.0);
        entry.setY(0.0);

        MapConfig.NodeConfig exit = new MapConfig.NodeConfig();
        exit.setId("n2");
        exit.setType("EXIT");
        exit.setX(100.0);
        exit.setY(0.0);

        cfg.setNodes(List.of(entry, exit));

        MapConfig.RoadConfig road = new MapConfig.RoadConfig();
        road.setId("r1");
        road.setFromNodeId("n1");
        road.setToNodeId("n2");
        road.setLength(100.0);
        road.setSpeedLimit(13.89); // 50 km/h
        road.setLaneCount(2);

        cfg.setRoads(new ArrayList<>(List.of(road)));
        cfg.setIntersections(List.of());
        cfg.setSpawnPoints(List.of());
        cfg.setDespawnPoints(List.of());
        return cfg;
    }

    @Test
    void lanesField_populatedListPassesValidation() {
        MapConfig cfg = buildMinimalMapConfig();
        cfg.getRoads()
                .get(0)
                .setLanes(
                        List.of(
                                new MapConfig.LaneConfig("sidewalk", 1.5, "both"),
                                new MapConfig.LaneConfig("driving", 3.5, "forward"),
                                new MapConfig.LaneConfig("driving", 3.5, "backward"),
                                new MapConfig.LaneConfig("sidewalk", 1.5, "both")));

        List<String> errors = mapValidator.validate(cfg);

        assertThat(errors)
                .as("MapConfig with populated lanes[] must pass validation — validator ignores the field")
                .isEmpty();
    }

    @Test
    void lanesField_nullPassesValidation() {
        // Regression contract: Phase 18/23 services never set lanes; validator must tolerate null.
        MapConfig cfg = buildMinimalMapConfig();
        assertThat(cfg.getRoads().get(0).getLanes()).isNull();

        List<String> errors = mapValidator.validate(cfg);

        assertThat(errors).isEmpty();
    }
}
