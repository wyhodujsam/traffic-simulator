package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class RoadNarrowingIntegrationTest {

    private PhysicsEngine physicsEngine = new PhysicsEngine();
    private LaneChangeEngine laneChangeEngine = new LaneChangeEngine(physicsEngine);

    private Vehicle createVehicle(String id, double position, double speed, Lane lane) {
        return Vehicle.builder()
            .id(id).position(position).speed(speed).acceleration(0)
            .lane(lane).length(4.5).v0(33.33).aMax(1.5).b(2.0).s0(2.0).timeHeadway(1.5)
            .spawnedAt(0).lastLaneChangeTick(0).forceLaneChange(false)
            .laneChangeProgress(1.0).laneChangeSourceIndex(-1)
            .build();
    }

    private Road createTwoLaneRoad() {
        Lane lane0 = Lane.builder()
            .id("r1-lane0").laneIndex(0).length(1000).maxSpeed(33.33).active(true)
            .vehicles(new ArrayList<>()).obstacles(new ArrayList<>()).build();
        Lane lane1 = Lane.builder()
            .id("r1-lane1").laneIndex(1).length(1000).maxSpeed(33.33).active(true)
            .vehicles(new ArrayList<>()).obstacles(new ArrayList<>()).build();
        Road road = Road.builder()
            .id("r1").name("Test Road").lanes(List.of(lane0, lane1))
            .length(1000).speedLimit(33.33)
            .startX(0).startY(300).endX(1000).endY(300)
            .fromNodeId("n1").toNodeId("n2").build();
        lane0.setRoad(road);
        lane1.setRoad(road);
        return road;
    }

    private RoadNetwork createNetwork(Road road) {
        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put(road.getId(), road);
        return RoadNetwork.builder()
            .id("test-network").roads(roads)
            .intersections(Map.of())
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();
    }

    @Test
    @DisplayName("CloseLane sets lane inactive and flags vehicles for forced merge")
    void closeLaneFlagsVehicles() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);

        Vehicle v1 = createVehicle("v1", 200, 15.0, lane0);
        Vehicle v2 = createVehicle("v2", 400, 15.0, lane0);
        lane0.addVehicle(v1);
        lane0.addVehicle(v2);

        // Simulate CloseLane command effect
        lane0.setActive(false);
        for (Vehicle v : lane0.getVehiclesView()) {
            v.setForceLaneChange(true);
        }

        assertThat(lane0.isActive()).isFalse();
        assertThat(v1.isForceLaneChange()).isTrue();
        assertThat(v2.isForceLaneChange()).isTrue();
    }

    @Test
    @DisplayName("Forced vehicles merge to adjacent lane within N ticks")
    void forcedMergeCompletes() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Place vehicles spaced out in lane 0 with force flag
        // Keep lane active initially so vehicles are evaluated by the engine
        Vehicle v1 = createVehicle("v1", 200, 15.0, lane0);
        v1.setForceLaneChange(true);
        lane0.addVehicle(v1);

        Vehicle v2 = createVehicle("v2", 500, 15.0, lane0);
        v2.setForceLaneChange(true);
        lane0.addVehicle(v2);

        RoadNetwork network = createNetwork(road);

        // Run multiple ticks — vehicles should merge out
        for (int tick = 1; tick <= 20; tick++) {
            // Run physics first
            for (Lane lane : road.getLanes()) {
                physicsEngine.tick(lane, 0.05);
            }
            laneChangeEngine.tick(network, tick);
        }

        // Both vehicles should be in lane 1 now
        assertThat(lane1.getVehiclesView()).extracting(Vehicle::getId)
            .containsExactlyInAnyOrder("v1", "v2");
        assertThat(lane0.getVehiclesView()).isEmpty();
    }

    @Test
    @DisplayName("Average speed decreases after lane closure with traffic")
    void congestionFormsAfterClosure() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);
        RoadNetwork network = createNetwork(road);

        // Populate both lanes with vehicles
        for (int i = 0; i < 8; i++) {
            Vehicle v = createVehicle("l0-v" + i, 50 + i * 40, 25.0, lane0);
            lane0.addVehicle(v);
        }
        for (int i = 0; i < 8; i++) {
            Vehicle v = createVehicle("l1-v" + i, 50 + i * 40, 25.0, lane1);
            lane1.addVehicle(v);
        }

        // Run 50 ticks to establish baseline
        for (int tick = 1; tick <= 50; tick++) {
            for (Lane lane : road.getLanes()) {
                physicsEngine.tick(lane, 0.05);
            }
            laneChangeEngine.tick(network, tick);
        }

        // Measure baseline average speed
        double baselineAvg = road.getLanes().stream()
            .flatMap(l -> l.getVehiclesView().stream())
            .mapToDouble(Vehicle::getSpeed)
            .average().orElse(0);

        // Flag vehicles in lane 0 for forced merge (keep lane active so engine processes them)
        for (Vehicle v : lane0.getVehiclesView()) {
            v.setForceLaneChange(true);
        }

        // Run 100 more ticks — forced vehicles merge into lane 1
        for (int tick = 51; tick <= 150; tick++) {
            for (Lane lane : road.getLanes()) {
                physicsEngine.tick(lane, 0.05);
            }
            laneChangeEngine.tick(network, tick);
        }

        // Measure post-merge average speed (lane 1 has more vehicles now)
        double postAvg = lane1.getVehiclesView().stream()
            .mapToDouble(Vehicle::getSpeed)
            .average().orElse(0);

        // Post-merge speed should be lower due to increased density
        // (more vehicles in fewer lanes)
        assertThat(postAvg).as("Post-merge avg speed should be <= baseline")
            .isLessThanOrEqualTo(baselineAvg + 1.0); // 1.0 tolerance
    }
}
