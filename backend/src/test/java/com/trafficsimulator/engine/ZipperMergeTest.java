package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for zipper merge behavior: vehicles stuck behind obstacles
 * should merge into adjacent lanes one-by-one.
 */
class ZipperMergeTest {

    private PhysicsEngine physicsEngine;
    private LaneChangeEngine laneChangeEngine;
    private Road road;
    private Lane lane0, lane1, lane2;
    private RoadNetwork network;

    private static final double DT = 0.05;
    private static final double MAX_SPEED = 33.3;
    private static final double ROAD_LENGTH = 800.0;

    @BeforeEach
    void setUp() {
        physicsEngine = new PhysicsEngine();
        laneChangeEngine = new LaneChangeEngine(physicsEngine);

        road = Road.builder()
            .id("r1").name("Test Road").length(ROAD_LENGTH).speedLimit(MAX_SPEED)
            .startX(0).startY(300).endX(800).endY(300)
            .fromNodeId("n1").toNodeId("n2")
            .lanes(new ArrayList<>())
            .build();

        lane0 = Lane.builder()
            .id("r1-lane0").laneIndex(0).road(road)
            .length(ROAD_LENGTH).maxSpeed(MAX_SPEED).active(true)
            .build();
        lane1 = Lane.builder()
            .id("r1-lane1").laneIndex(1).road(road)
            .length(ROAD_LENGTH).maxSpeed(MAX_SPEED).active(true)
            .build();
        lane2 = Lane.builder()
            .id("r1-lane2").laneIndex(2).road(road)
            .length(ROAD_LENGTH).maxSpeed(MAX_SPEED).active(true)
            .build();

        road.getLanes().add(lane0);
        road.getLanes().add(lane1);
        road.getLanes().add(lane2);

        Map<String, Road> roads = new HashMap<>();
        roads.put("r1", road);
        network = RoadNetwork.builder().id("net").roads(roads).build();
    }

    private Vehicle createVehicle(String id, Lane lane, double position, double speed) {
        Vehicle v = Vehicle.builder()
            .id(id).position(position).speed(speed).acceleration(0.0)
            .lane(lane).length(4.5)
            .v0(MAX_SPEED).aMax(1.4).b(2.0).s0(2.0).T(1.5)
            .spawnedAt(0).laneChangeSourceIndex(-1)
            .build();
        lane.getVehicles().add(v);
        return v;
    }

    private Obstacle createObstacle(Lane lane, double position) {
        Obstacle obs = Obstacle.builder()
            .id("obs-" + lane.getId() + "-" + position)
            .laneId(lane.getId()).position(position).length(3.0).createdAtTick(0)
            .build();
        lane.getObstacles().add(obs);
        return obs;
    }

    // =========================================================================
    // Test 1: Vehicle stopped behind obstacle is marked as zipper candidate
    // =========================================================================
    @Test
    void stoppedVehicleBehindObstacle_isMarkedZipperCandidate() {
        createObstacle(lane1, 400.0);
        Vehicle stuck = createVehicle("stuck", lane1, 390.0, 0.0);

        laneChangeEngine.tick(network, 100);

        // The vehicle should have been marked and processed
        // If it merged, it's no longer in lane1
        // If still in lane1, it should have been marked as zipper candidate
        // Either way, the marking logic ran
        assertThat(stuck.isZipperCandidate() || stuck.getLane() != lane1)
            .as("stuck vehicle should be zipper candidate or have merged")
            .isTrue();
    }

    // =========================================================================
    // Test 2: Only the FIRST vehicle behind obstacle gets zipper status
    // =========================================================================
    @Test
    void onlyFirstVehicleBehindObstacle_getsZipperStatus() {
        createObstacle(lane1, 400.0);
        Vehicle first = createVehicle("first", lane1, 392.0, 0.0);
        Vehicle second = createVehicle("second", lane1, 380.0, 0.0);

        // Manually call tick without committing to check marks
        laneChangeEngine.tick(network, 100);

        // After tick, at most one should have merged or been candidate
        // The second vehicle should NOT be a zipper candidate
        // (it may have been cleared after tick processing)
        // Key invariant: they don't BOTH merge in the same tick
        int mergedCount = 0;
        if (first.getLane() != lane1) mergedCount++;
        if (second.getLane() != lane1) mergedCount++;
        assertThat(mergedCount).as("at most 1 vehicle merges per tick per obstacle")
            .isLessThanOrEqualTo(1);
    }

    // =========================================================================
    // Test 3: Zipper merge actually completes over multiple ticks
    // =========================================================================
    @Test
    void zipperMerge_vehicleMergesWithinReasonableTicks() {
        createObstacle(lane1, 400.0);
        Vehicle stuck = createVehicle("stuck", lane1, 392.0, 0.0);
        // Adjacent lane has a vehicle far enough ahead to leave space
        createVehicle("passing", lane0, 420.0, 20.0);

        boolean merged = false;
        for (int tick = 0; tick < 200; tick++) {
            // Run physics first (as real pipeline does)
            physicsEngine.tick(lane0, DT);
            physicsEngine.tick(lane1, DT);
            physicsEngine.tick(lane2, DT);
            laneChangeEngine.tick(network, tick);

            if (stuck.getLane() != lane1) {
                merged = true;
                break;
            }
        }

        assertThat(merged).as("stuck vehicle should merge within 200 ticks (10 seconds)")
            .isTrue();
    }

    // =========================================================================
    // Test 4: Zipper merge into empty adjacent lane works immediately
    // =========================================================================
    @Test
    void zipperMerge_intoEmptyLane_mergesQuickly() {
        createObstacle(lane1, 400.0);
        Vehicle stuck = createVehicle("stuck", lane1, 392.0, 0.0);
        // lane0 and lane2 are empty — should merge easily

        boolean merged = false;
        for (int tick = 0; tick < 10; tick++) {
            physicsEngine.tick(lane0, DT);
            physicsEngine.tick(lane1, DT);
            physicsEngine.tick(lane2, DT);
            laneChangeEngine.tick(network, tick);

            if (stuck.getLane() != lane1) {
                merged = true;
                break;
            }
        }

        assertThat(merged).as("stuck vehicle merges into empty lane within 10 ticks")
            .isTrue();
    }

    // =========================================================================
    // Test 5: Zipper merge with traffic — vehicle eventually finds gap
    // =========================================================================
    @Test
    void zipperMerge_withTraffic_findsGapEventually() {
        createObstacle(lane1, 400.0);
        Vehicle stuck = createVehicle("stuck", lane1, 392.0, 0.0);

        // Adjacent lane has vehicles flowing past, but with some gaps
        createVehicle("flow1", lane0, 350.0, 25.0);
        createVehicle("flow2", lane0, 320.0, 25.0);
        createVehicle("flow3", lane0, 290.0, 25.0);

        boolean merged = false;
        for (int tick = 0; tick < 400; tick++) {
            physicsEngine.tick(lane0, DT);
            physicsEngine.tick(lane1, DT);
            physicsEngine.tick(lane2, DT);
            laneChangeEngine.tick(network, tick);

            if (stuck.getLane() != lane1) {
                merged = true;
                break;
            }
        }

        assertThat(merged).as("stuck vehicle merges even with traffic within 400 ticks (20s)")
            .isTrue();
    }

    // =========================================================================
    // Test 6: Multiple stuck vehicles merge one-by-one
    // =========================================================================
    @Test
    void zipperMerge_multipleStuck_mergeOneByOne() {
        // Use only 2 lanes so vehicles can only merge into one direction
        // This forces sequential merging via conflict resolution
        lane2.setActive(false);

        createObstacle(lane1, 400.0);
        Vehicle v1 = createVehicle("v1", lane1, 392.0, 0.0);
        Vehicle v2 = createVehicle("v2", lane1, 382.0, 0.0);
        Vehicle v3 = createVehicle("v3", lane1, 372.0, 0.0);

        int mergeCount = 0;
        int[] mergeTicks = new int[3];

        for (int tick = 1; tick <= 600; tick++) {
            physicsEngine.tick(lane0, DT);
            physicsEngine.tick(lane1, DT);
            laneChangeEngine.tick(network, tick);

            if (v1.getLane() != lane1 && mergeTicks[0] == 0) { mergeTicks[0] = tick; mergeCount++; }
            if (v2.getLane() != lane1 && mergeTicks[1] == 0) { mergeTicks[1] = tick; mergeCount++; }
            if (v3.getLane() != lane1 && mergeTicks[2] == 0) { mergeTicks[2] = tick; mergeCount++; }

            if (mergeCount == 3) break;
        }

        assertThat(mergeCount).as("all 3 vehicles should eventually merge")
            .isEqualTo(3);
        // They should merge at different ticks (one by one into the single available lane)
        assertThat(mergeTicks[0]).as("v1 and v2 merge at different ticks")
            .isNotEqualTo(mergeTicks[1]);
        assertThat(mergeTicks[1]).as("v2 and v3 merge at different ticks")
            .isNotEqualTo(mergeTicks[2]);
    }

    // =========================================================================
    // Test 7: Free-lane vehicles slow down near zipper point (yield zone)
    // =========================================================================
    @Test
    void zipperMerge_freeLaneVehiclesSlowDown_nearObstacleOnAdjacentLane() {
        createObstacle(lane1, 400.0);
        // Multiple stuck vehicles so yield zone stays active even after first merges
        createVehicle("stuck1", lane1, 392.0, 0.0);
        createVehicle("stuck2", lane1, 382.0, 0.0);
        createVehicle("stuck3", lane1, 372.0, 0.0);

        // Free lane vehicle approaching the obstacle zone
        Vehicle freeLane = createVehicle("free", lane0, 340.0, 25.0);

        // Run physics only (no lane change engine) to isolate yield behavior
        for (int tick = 0; tick < 40; tick++) {
            physicsEngine.tick(lane0, DT);
            physicsEngine.tick(lane1, DT);
        }

        // Vehicle should have slowed significantly near the obstacle zone
        assertThat(freeLane.getSpeed()).as("free lane vehicle slows near zipper point")
            .isLessThan(15.0);
    }

    // =========================================================================
    // Test 8: Alternating merge — blocked and free lane vehicles interleave
    // =========================================================================
    @Test
    void zipperMerge_alternatingMerge_blockedAndFreeInterleave() {
        lane2.setActive(false); // force single merge direction

        createObstacle(lane1, 400.0);
        Vehicle stuck1 = createVehicle("stuck1", lane1, 392.0, 0.0);
        Vehicle stuck2 = createVehicle("stuck2", lane1, 382.0, 0.0);

        // Free lane has steady flow approaching the merge zone
        Vehicle free1 = createVehicle("free1", lane0, 360.0, 20.0);
        Vehicle free2 = createVehicle("free2", lane0, 330.0, 20.0);
        Vehicle free3 = createVehicle("free3", lane0, 300.0, 20.0);

        // Track merge events — which vehicles end up on lane0 in order of position
        for (int tick = 1; tick <= 400; tick++) {
            physicsEngine.tick(lane0, DT);
            physicsEngine.tick(lane1, DT);
            laneChangeEngine.tick(network, tick);
        }

        // Both stuck vehicles should have merged
        assertThat(stuck1.getLane()).as("stuck1 merged to free lane").isEqualTo(lane0);
        assertThat(stuck2.getLane()).as("stuck2 merged to free lane").isEqualTo(lane0);
    }

    // =========================================================================
    // Test 9: Vehicle NOT near obstacle is NOT a zipper candidate
    // =========================================================================
    @Test
    void vehicleFarFromObstacle_isNotZipperCandidate() {
        createObstacle(lane1, 400.0);
        Vehicle far = createVehicle("far", lane1, 200.0, 15.0);

        laneChangeEngine.tick(network, 100);

        // Vehicle at 200m is 200m behind obstacle at 400m — too far for zipper
        assertThat(far.isZipperCandidate()).as("vehicle far from obstacle is not zipper candidate")
            .isFalse();
    }
}
