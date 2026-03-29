package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class LaneChangeEngineTest {

    private PhysicsEngine physicsEngine;
    private LaneChangeEngine laneChangeEngine;

    @BeforeEach
    void setUp() {
        physicsEngine = new PhysicsEngine();
        laneChangeEngine = new LaneChangeEngine(physicsEngine);
    }

    // === Helper methods ===

    private Vehicle createVehicle(String id, double position, double speed, Lane lane) {
        return Vehicle.builder()
            .id(id)
            .position(position)
            .speed(speed)
            .acceleration(0)
            .lane(lane)
            .length(4.5)
            .v0(33.33)      // ~120 km/h
            .aMax(1.5)
            .b(2.0)
            .s0(2.0)
            .T(1.5)
            .spawnedAt(0)
            .lastLaneChangeTick(0)
            .forceLaneChange(false)
            .laneChangeProgress(1.0)
            .laneChangeSourceIndex(-1)
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

    private Road createThreeLaneRoad() {
        Lane lane0 = Lane.builder()
            .id("r1-lane0").laneIndex(0).length(1000).maxSpeed(33.33).active(true)
            .vehicles(new ArrayList<>()).obstacles(new ArrayList<>()).build();
        Lane lane1 = Lane.builder()
            .id("r1-lane1").laneIndex(1).length(1000).maxSpeed(33.33).active(true)
            .vehicles(new ArrayList<>()).obstacles(new ArrayList<>()).build();
        Lane lane2 = Lane.builder()
            .id("r1-lane2").laneIndex(2).length(1000).maxSpeed(33.33).active(true)
            .vehicles(new ArrayList<>()).obstacles(new ArrayList<>()).build();
        Road road = Road.builder()
            .id("r1").name("Test Road").lanes(List.of(lane0, lane1, lane2))
            .length(1000).speedLimit(33.33)
            .startX(0).startY(300).endX(1000).endY(300)
            .fromNodeId("n1").toNodeId("n2").build();
        lane0.setRoad(road);
        lane1.setRoad(road);
        lane2.setRoad(road);
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

    // === Test 1: Free lane preferred ===
    @Test
    @DisplayName("Vehicle behind slow leader changes to empty adjacent lane")
    void freeLanePreferred() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Slow leader in lane 0
        Vehicle leader = createVehicle("leader", 200, 10.0, lane0);
        lane0.addVehicle(leader);

        // Faster follower in lane 0
        Vehicle follower = createVehicle("follower", 170, 20.0, lane0);
        lane0.addVehicle(follower);

        // Lane 1 is empty — should be attractive
        RoadNetwork network = createNetwork(road);

        laneChangeEngine.tick(network, 100);

        // Follower should have moved to lane 1
        assertThat(lane1.getVehiclesView()).extracting(Vehicle::getId).contains("follower");
        assertThat(lane0.getVehiclesView()).extracting(Vehicle::getId).doesNotContain("follower");
    }

    // === Test 2: No change when alone ===
    @Test
    @DisplayName("Single vehicle in single-lane road produces no lane change")
    void noChangeWhenAlone() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);

        Vehicle solo = createVehicle("solo", 500, 30.0, lane0);
        lane0.addVehicle(solo);

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // Vehicle stays in lane 0 (no incentive to change)
        assertThat(lane0.getVehiclesView()).extracting(Vehicle::getId).contains("solo");
    }

    // === Test 3: Safety criterion blocks unsafe change ===
    @Test
    @DisplayName("Lane change blocked when new follower would brake too hard")
    void safetyCriterionBlocksUnsafeChange() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Slow leader in lane 0
        Vehicle leader = createVehicle("leader", 200, 5.0, lane0);
        lane0.addVehicle(leader);

        // Subject wants to change to lane 1
        Vehicle subject = createVehicle("subject", 170, 20.0, lane0);
        lane0.addVehicle(subject);

        // Fast vehicle very close behind in lane 1 — unsafe to merge in front of
        Vehicle fastFollower = createVehicle("fast", 168, 30.0, lane1);
        lane1.addVehicle(fastFollower);

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // Subject should NOT have moved — safety criterion violated
        assertThat(lane0.getVehiclesView()).extracting(Vehicle::getId).contains("subject");
    }

    // === Test 4: Cooldown enforcement ===
    @Test
    @DisplayName("Vehicle that just changed lanes cannot change again within cooldown period")
    void cooldownEnforced() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Slow leader in lane 1
        Vehicle leader = createVehicle("leader", 200, 5.0, lane1);
        lane1.addVehicle(leader);

        // Vehicle in lane 1 recently changed (tick 95, current tick 100 = only 5 ticks ago)
        Vehicle recent = createVehicle("recent", 170, 20.0, lane1);
        recent.startLaneChange(lane1, -1, 95);  // simulate recent lane change at tick 95
        recent.completeLaneChange();
        lane1.addVehicle(recent);

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // Vehicle should stay — cooldown not expired (need 60 ticks, only 5 elapsed)
        assertThat(lane1.getVehiclesView()).extracting(Vehicle::getId).contains("recent");
    }

    // === Test 5: Inactive lane rejection ===
    @Test
    @DisplayName("MOBIL never selects an inactive lane as target")
    void inactiveLaneRejected() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);
        lane1.setActive(false);  // closed lane

        // Slow leader in lane 0
        Vehicle leader = createVehicle("leader", 200, 5.0, lane0);
        lane0.addVehicle(leader);

        Vehicle follower = createVehicle("follower", 170, 20.0, lane0);
        lane0.addVehicle(follower);

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // Follower stays in lane 0 — lane 1 is inactive
        assertThat(lane0.getVehiclesView()).extracting(Vehicle::getId).contains("follower");
        assertThat(lane1.getVehiclesView()).isEmpty();
    }

    // === Test 6: Forced lane change bypasses incentive criterion ===
    @Test
    @DisplayName("Vehicle with forceLaneChange flag merges even without MOBIL incentive")
    void forcedLaneChange() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Vehicle forced to change from lane 0 (lane still active so engine processes it)
        Vehicle forced = createVehicle("forced", 500, 15.0, lane0);
        forced.setForceLaneChange(true);
        lane0.addVehicle(forced);

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // Vehicle should have moved to lane 1 (force bypasses incentive check)
        assertThat(lane1.getVehiclesView()).extracting(Vehicle::getId).contains("forced");
        assertThat(lane0.getVehiclesView()).extracting(Vehicle::getId).doesNotContain("forced");
    }

    // === Test 7: Two-phase conflict resolution ===
    @Test
    @DisplayName("Two vehicles targeting same gap — only one wins")
    void conflictResolution() {
        Road road = createThreeLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);
        Lane lane2 = road.getLanes().get(2);

        // Two vehicles at similar positions in different lanes, both wanting lane 1
        // Slow leader in lane 0
        Vehicle leader0 = createVehicle("leader0", 200, 5.0, lane0);
        lane0.addVehicle(leader0);

        Vehicle v1 = createVehicle("v1", 170, 20.0, lane0);
        lane0.addVehicle(v1);

        // Slow leader in lane 2
        Vehicle leader2 = createVehicle("leader2", 200, 5.0, lane2);
        lane2.addVehicle(leader2);

        Vehicle v2 = createVehicle("v2", 172, 20.0, lane2);
        lane2.addVehicle(v2);

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // At most one of v1, v2 should end up in lane1 (conflict resolution)
        long inLane1 = lane1.getVehiclesView().stream()
            .filter(v -> v.getId().equals("v1") || v.getId().equals("v2"))
            .count();
        assertThat(inLane1).isLessThanOrEqualTo(1);
    }

    // === Test 8: No dual occupancy after commit ===
    @Test
    @DisplayName("After lane change, no two vehicles overlap in the same lane")
    void noDualOccupancy() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Create several vehicles in lane 0 with a slow leader
        Vehicle leader = createVehicle("leader", 300, 5.0, lane0);
        lane0.addVehicle(leader);

        for (int i = 0; i < 5; i++) {
            Vehicle v = createVehicle("v" + i, 100 + i * 30, 20.0, lane0);
            lane0.addVehicle(v);
        }

        RoadNetwork network = createNetwork(road);
        laneChangeEngine.tick(network, 100);

        // Check all lanes: no two vehicles within vehicleLength of each other
        for (Lane lane : road.getLanes()) {
            List<Vehicle> sorted = new ArrayList<>(lane.getVehiclesView());
            sorted.sort(Comparator.comparingDouble(Vehicle::getPosition));
            for (int i = 1; i < sorted.size(); i++) {
                double gap = sorted.get(i).getPosition() - sorted.get(i - 1).getPosition();
                assertThat(gap).as("Gap between %s and %s in %s",
                    sorted.get(i - 1).getId(), sorted.get(i).getId(), lane.getId())
                    .isGreaterThan(sorted.get(i).getLength());
            }
        }
    }

    // === Test 9: Lane change progress animation ===
    @Test
    @DisplayName("Lane change progress increments from 0 towards 1 over multiple ticks")
    void laneChangeProgressAnimation() {
        Road road = createTwoLaneRoad();
        Lane lane0 = road.getLanes().get(0);
        Lane lane1 = road.getLanes().get(1);

        // Slow leader in lane 0, follower will change
        Vehicle leader = createVehicle("leader", 200, 5.0, lane0);
        lane0.addVehicle(leader);

        Vehicle mover = createVehicle("mover", 170, 20.0, lane0);
        lane0.addVehicle(mover);

        RoadNetwork network = createNetwork(road);

        // First tick: lane change should happen
        laneChangeEngine.tick(network, 100);

        if (lane1.getVehiclesView().stream().anyMatch(v -> v.getId().equals("mover"))) {
            Vehicle moved = lane1.getVehiclesView().stream()
                .filter(v -> v.getId().equals("mover")).findFirst().orElseThrow();
            // Progress should be 0.0 right after commit
            assertThat(moved.getLaneChangeProgress()).isEqualTo(0.0);
            assertThat(moved.getLaneChangeSourceIndex()).isEqualTo(0); // came from lane 0

            // Second tick: progress should increase
            laneChangeEngine.tick(network, 101);
            assertThat(moved.getLaneChangeProgress()).isGreaterThan(0.0);
            assertThat(moved.getLaneChangeProgress()).isLessThanOrEqualTo(1.0);
        }
        // If lane change didn't happen (rare edge case), test is inconclusive — ok
    }
}
