package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

/**
 * Plan 25-03 Task 2 — D-12 perturbation hook unit tests. Verifies the slow-leader pulse contract:
 * the deterministic "vehicle 0" (min spawnedAt + lex tie-break by id) gets clamped to {@code
 * targetSpeed} for {@code durationTicks} ticks starting at {@code tick}; everyone else (and other
 * ticks) returns null.
 */
class PerturbationManagerTest {

    private SimulationEngine engine;
    private PerturbationManager pm;
    private RoadNetwork network;
    private Lane lane;
    private Vehicle v0;
    private Vehicle v1;

    @BeforeEach
    void setup() {
        engine = mock(SimulationEngine.class);
        pm = new PerturbationManager(engine);

        // Real Lane fixture (Lane.java has @Builder with @Builder.Default vehicles list).
        lane = Lane.builder()
                .id("r0-lane0")
                .laneIndex(0)
                .length(250.0)
                .maxSpeed(22.2)
                .active(true)
                .build();

        // Vehicle 0: smaller spawnedAt + lex-smaller id ("aaa") → wins (spawnedAt, id) ordering.
        v0 = Vehicle.builder()
                .id("aaa")
                .position(50.0)
                .speed(20.0)
                .acceleration(0.0)
                .lane(lane)
                .length(4.5)
                .v0(33.3)
                .aMax(1.4)
                .b(2.0)
                .s0(2.0)
                .timeHeadway(1.5)
                .spawnedAt(0L)
                .build();
        // Vehicle 1: larger spawnedAt → never wins vehicle-0 selection.
        v1 = Vehicle.builder()
                .id("bbb")
                .position(150.0)
                .speed(20.0)
                .acceleration(0.0)
                .lane(lane)
                .length(4.5)
                .v0(33.3)
                .aMax(1.4)
                .b(2.0)
                .s0(2.0)
                .timeHeadway(1.5)
                .spawnedAt(5L)
                .build();
        // Real addVehicle() — Lane keeps internal sorted ArrayList.
        lane.addVehicle(v0);
        lane.addVehicle(v1);

        // Road is mocked because we only need getLanes().
        Road road = mock(Road.class);
        when(road.getLanes()).thenReturn(List.of(lane));

        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r0", road);

        network = mock(RoadNetwork.class);
        when(network.getRoads()).thenReturn(roads);
        when(engine.getRoadNetwork()).thenReturn(network);
    }

    private void setPerturbation(long tick, int idx, double speed, long duration) {
        MapConfig.PerturbationConfig cfg =
                new MapConfig.PerturbationConfig(tick, idx, speed, duration);
        when(network.getPerturbation()).thenReturn(cfg);
    }

    @Test
    void getActiveV0_returnsTargetSpeed_inWindowAndMatchVehicle() {
        setPerturbation(200L, 0, 5.0, 60L);
        pm.clearCache();
        assertThat(pm.getActiveV0(v0, 230L)).isEqualTo(5.0);
    }

    @Test
    void getActiveV0_returnsNull_beforeWindow() {
        setPerturbation(200L, 0, 5.0, 60L);
        pm.clearCache();
        assertThat(pm.getActiveV0(v0, 100L)).isNull();
    }

    @Test
    void getActiveV0_returnsNull_afterWindow() {
        setPerturbation(200L, 0, 5.0, 60L);
        pm.clearCache();
        // window = [200, 260) — exclusive end
        assertThat(pm.getActiveV0(v0, 260L)).isNull();
    }

    @Test
    void getActiveV0_returnsNull_forNonMatchingVehicle() {
        setPerturbation(200L, 0, 5.0, 60L);
        pm.clearCache();
        assertThat(pm.getActiveV0(v1, 230L)).isNull();
    }

    @Test
    void getActiveV0_returnsNull_whenNoPerturbationConfigured() {
        when(network.getPerturbation()).thenReturn(null);
        pm.clearCache();
        assertThat(pm.getActiveV0(v0, 230L)).isNull();
    }
}
