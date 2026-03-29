package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleSpawnerTest {

    private VehicleSpawner spawner;
    private RoadNetwork network;
    private Lane lane0;

    @BeforeEach
    void setUp() {
        spawner = new VehicleSpawner();
        spawner.setVehiclesPerSecond(1.0);

        Road road = Road.builder()
            .id("r1").name("Test Road").length(800.0).speedLimit(33.3)
            .startX(0).startY(0).endX(800).endY(0)
            .fromNodeId("n1").toNodeId("n2")
            .lanes(new ArrayList<>())
            .build();

        lane0 = Lane.builder()
            .id("r1-lane0").laneIndex(0).road(road)
            .length(800.0).maxSpeed(33.3).active(true)
            .build();
        road.getLanes().add(lane0);

        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r1", road);

        network = RoadNetwork.builder()
            .id("test")
            .roads(roads)
            .intersections(new LinkedHashMap<>())
            .spawnPoints(List.of(new SpawnPoint("r1", 0, 0.0)))
            .despawnPoints(List.of(new DespawnPoint("r1", 0, 800.0)))
            .build();
    }

    @Test
    void tick_atOneVehiclePerSecond_spawnsAfterOneSecond() {
        // 20 ticks at 50ms = 1.0 second
        for (int i = 0; i < 20; i++) {
            spawner.tick(0.05, network, i);
        }
        assertThat(lane0.getVehiclesView()).hasSize(1);
    }

    @Test
    void tick_spawnsVehicleWithCorrectInitialState() {
        spawner.tick(1.0, network, 1);

        Vehicle v = lane0.getVehiclesView().get(0);
        assertThat(v.getId()).isNotNull();
        assertThat(v.getPosition()).isEqualTo(0.0);
        // Vehicle spawns at 50% of lane maxSpeed to avoid braking cascade
        assertThat(v.getSpeed()).isEqualTo(0.5 * lane0.getMaxSpeed());
        assertThat(v.getLane()).isSameAs(lane0);
        assertThat(v.getLength()).isEqualTo(4.5);
        assertThat(v.getSpawnedAt()).isEqualTo(1);
    }

    @Test
    void tick_idmParametersHaveTwentyPercentNoise() {
        // Spawn many vehicles to check parameter distribution
        spawner.setVehiclesPerSecond(100.0);
        spawner.tick(1.0, network, 1);

        Vehicle v = lane0.getVehiclesView().get(0);
        assertThat(v.getV0()).isBetween(33.3 * 0.8, 33.3 * 1.2);
        assertThat(v.getAMax()).isBetween(1.4 * 0.8, 1.4 * 1.2);
        assertThat(v.getB()).isBetween(2.0 * 0.8, 2.0 * 1.2);
        assertThat(v.getS0()).isEqualTo(2.0); // s0 is NOT randomised
        assertThat(v.getT()).isBetween(1.5 * 0.8, 1.5 * 1.2);
    }

    @Test
    void tick_overlapPrevention_blocksDoubleSpawnAtSamePosition() {
        // Spawn one vehicle at position 0
        spawner.tick(1.0, network, 1);
        assertThat(lane0.getVehiclesView()).hasSize(1);

        // Try to spawn another — should be blocked (position 0 occupied within 6.5m gap)
        spawner.tick(1.0, network, 2);
        assertThat(lane0.getVehiclesView()).hasSize(1);
    }

    @Test
    void tick_noSpawnPoints_doesNothing() {
        RoadNetwork emptyNetwork = RoadNetwork.builder()
            .id("empty")
            .roads(new LinkedHashMap<>())
            .intersections(new LinkedHashMap<>())
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();

        spawner.tick(1.0, emptyNetwork, 1);
        // No exception, no vehicles
    }

    @Test
    void setVehiclesPerSecond_changesSpawnRate() {
        spawner.setVehiclesPerSecond(2.0);
        // At 2 veh/s, 0.6s (12 ticks * 50ms) should spawn at least 1 vehicle.
        // Using 12 ticks to avoid floating-point edge on exactly 0.5s boundary.
        for (int i = 0; i < 12; i++) {
            spawner.tick(0.05, network, i);
        }
        assertThat(lane0.getVehiclesView()).hasSize(1);
    }

    @Test
    void reset_clearsAccumulatorAndIndex() {
        spawner.tick(0.5, network, 1); // accumulate 0.5
        spawner.reset();
        // After reset, need full 1.0s accumulation again
        spawner.tick(0.5, network, 2);
        assertThat(lane0.getVehiclesView()).isEmpty();
    }

    @Test
    void tick_blockedSpawnPoints_neverCreatesInfiniteLoop() {
        // Fill the lane with a vehicle at position 0 to block the spawn point
        spawner.tick(1.0, network, 1);
        assertThat(lane0.getVehiclesView()).hasSize(1);

        // Attempt many ticks — should return without hanging
        for (int i = 0; i < 100; i++) {
            spawner.tick(1.0, network, i + 2);
        }
        // Vehicle is still there, no infinite loop occurred
        assertThat(lane0.getVehiclesView()).hasSize(1);
    }
}
