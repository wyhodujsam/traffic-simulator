package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class IntersectionManagerTest {

    private IntersectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new IntersectionManager();
    }

    // ---- Helper: build a minimal 4-way SIGNAL network ----

    private static Road buildRoad(String id, double length, double startX, double startY,
                                  double endX, double endY, String fromNode, String toNode) {
        Road road = Road.builder()
            .id(id).name(id).length(length).speedLimit(13.9)
            .startX(startX).startY(startY).endX(endX).endY(endY)
            .fromNodeId(fromNode).toNodeId(toNode)
            .lanes(new ArrayList<>())
            .build();
        Lane lane = Lane.builder()
            .id(id + "-lane0").laneIndex(0).road(road)
            .length(length).maxSpeed(13.9).active(true)
            .build();
        road.getLanes().add(lane);
        return road;
    }

    private static Vehicle buildVehicle(String id, double position, double speed, Lane lane, long spawnedAt) {
        Vehicle v = Vehicle.builder()
            .id(id).position(position).speed(speed).lane(lane)
            .length(4.5).v0(13.9).aMax(1.5).b(2.0).s0(2.0).T(1.5)
            .spawnedAt(spawnedAt).laneChangeProgress(1.0).laneChangeSourceIndex(-1)
            .build();
        lane.addVehicle(v);
        return v;
    }

    /**
     * Builds a 4-way network with SIGNAL intersection. Roads:
     * r_north_in (from north to center), r_south_in, r_west_in, r_east_in (inbound)
     * r_north_out, r_south_out, r_west_out, r_east_out (outbound)
     * Light: GREEN for north/south, RED for west/east
     */
    private RoadNetwork buildFourWaySignalNetwork(Set<String> greenRoads) {
        Map<String, Road> roads = new LinkedHashMap<>();
        // Inbound roads (250m each, pointing toward center at 400,300)
        roads.put("r_north_in", buildRoad("r_north_in", 250, 400, 50, 400, 300, "n_north", "n_center"));
        roads.put("r_south_in", buildRoad("r_south_in", 250, 400, 550, 400, 300, "n_south", "n_center"));
        roads.put("r_west_in",  buildRoad("r_west_in",  350, 50, 300, 400, 300, "n_west", "n_center"));
        roads.put("r_east_in",  buildRoad("r_east_in",  350, 750, 300, 400, 300, "n_east", "n_center"));
        // Outbound roads
        roads.put("r_north_out", buildRoad("r_north_out", 250, 400, 300, 420, 50, "n_center", "n_north_exit"));
        roads.put("r_south_out", buildRoad("r_south_out", 250, 400, 300, 380, 550, "n_center", "n_south_exit"));
        roads.put("r_west_out",  buildRoad("r_west_out",  350, 400, 300, 50, 320, "n_center", "n_west_exit"));
        roads.put("r_east_out",  buildRoad("r_east_out",  350, 400, 300, 750, 280, "n_center", "n_east_exit"));

        // Build traffic light
        List<TrafficLightPhase> phases = new ArrayList<>();
        phases.add(TrafficLightPhase.builder()
            .greenRoadIds(greenRoads)
            .durationMs(30000)
            .type(TrafficLightPhase.PhaseType.GREEN)
            .build());
        TrafficLight light = TrafficLight.builder()
            .intersectionId("n_center")
            .phases(phases)
            .currentPhaseIndex(0)
            .phaseElapsedMs(0)
            .build();

        Intersection ixtn = Intersection.builder()
            .id("n_center")
            .type(IntersectionType.SIGNAL)
            .inboundRoadIds(new ArrayList<>(List.of("r_north_in", "r_south_in", "r_west_in", "r_east_in")))
            .outboundRoadIds(new ArrayList<>(List.of("r_north_out", "r_south_out", "r_west_out", "r_east_out")))
            .connectedRoadIds(new ArrayList<>(roads.keySet()))
            .trafficLight(light)
            .build();

        Map<String, Intersection> intersections = new LinkedHashMap<>();
        intersections.put(ixtn.getId(), ixtn);

        return RoadNetwork.builder()
            .id("test-4way")
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();
    }

    // ---- Task 3 Tests ----

    @Test
    void stopLinesGeneratedForRedLight() {
        // GREEN only for north/south — west/east should get stop lines
        RoadNetwork network = buildFourWaySignalNetwork(Set.of("r_north_in", "r_south_in"));

        Map<String, Double> stopLines = manager.computeStopLines(network);

        // west_in and east_in lanes should have stop lines (RED)
        assertThat(stopLines).containsKey("r_west_in-lane0")
            .containsKey("r_east_in-lane0");
    }

    @Test
    void noStopLinesForGreenLight() {
        // GREEN for all 4 inbound roads
        RoadNetwork network = buildFourWaySignalNetwork(
            Set.of("r_north_in", "r_south_in", "r_west_in", "r_east_in"));

        Map<String, Double> stopLines = manager.computeStopLines(network);

        // No red-light stop lines (box-blocking may still apply if outbound empty check fails,
        // but with empty outbound lanes, all should pass)
        assertThat(stopLines).doesNotContainKey("r_north_in-lane0")
            .doesNotContainKey("r_south_in-lane0")
            .doesNotContainKey("r_west_in-lane0")
            .doesNotContainKey("r_east_in-lane0");
    }

    @Test
    void vehicleTransferredOnGreenLight() {
        RoadNetwork network = buildFourWaySignalNetwork(Set.of("r_north_in", "r_south_in"));
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        // Place vehicle near end of inbound road (past stop line threshold)
        buildVehicle("v1", 249.0, 5.0, inLane, 0);

        manager.processTransfers(network, 1);

        // Vehicle should have been removed from inbound lane
        assertThat(inLane.getVehiclesView()).isEmpty();

        // Vehicle should be on one of the outbound roads at buffer position (intersection edge)
        boolean foundOnOutbound = false;
        for (String outId : List.of("r_south_out", "r_west_out", "r_east_out")) {
            Road outRoad = network.getRoads().get(outId);
            if (!outRoad.getLanes().get(0).getVehiclesView().isEmpty()) {
                Vehicle transferred = outRoad.getLanes().get(0).getVehiclesView().get(0);
                assertThat(transferred.getId()).isEqualTo("v1");
                assertThat(transferred.getPosition()).isGreaterThanOrEqualTo(0.0);
                foundOnOutbound = true;
                break;
            }
        }
        assertThat(foundOnOutbound).isTrue();
    }

    @Test
    void vehicleBlockedOnRedLight() {
        // Only west/east are green — north is RED
        RoadNetwork network = buildFourWaySignalNetwork(Set.of("r_west_in", "r_east_in"));
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        buildVehicle("v1", 249.0, 5.0, inLane, 0);

        manager.processTransfers(network, 1);

        // Vehicle should still be on inbound lane (RED light)
        assertThat(inLane.getVehiclesView()).hasSize(1);
        assertThat(inLane.getVehiclesView().get(0).getId()).isEqualTo("v1");
    }

    @Test
    void boxBlockingPreventsEntry() {
        RoadNetwork network = buildFourWaySignalNetwork(Set.of("r_north_in", "r_south_in"));
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        // Place vehicle at end of inbound road
        buildVehicle("v1", 249.0, 5.0, inLane, 0);

        // Block ALL outbound roads (except r_north_out which is U-turn) with vehicle at position 2.0 (< MIN_ENTRY_GAP=7.0)
        for (String outId : List.of("r_south_out", "r_west_out", "r_east_out")) {
            Road outRoad = network.getRoads().get(outId);
            Lane outLane = outRoad.getLanes().get(0);
            buildVehicle("blocker_" + outId, 2.0, 0.0, outLane, 0);
        }

        manager.processTransfers(network, 1);

        // Vehicle should NOT be transferred — all outbound roads blocked
        assertThat(inLane.getVehiclesView()).hasSize(1);
        assertThat(inLane.getVehiclesView().get(0).getId()).isEqualTo("v1");
    }

    @Test
    void boxBlockingAllowsWhenOutboundClear() {
        RoadNetwork network = buildFourWaySignalNetwork(Set.of("r_north_in", "r_south_in"));
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        buildVehicle("v1", 249.0, 5.0, inLane, 0);
        // All outbound roads are empty — no box blocking

        manager.processTransfers(network, 1);

        // Vehicle should be transferred
        assertThat(inLane.getVehiclesView()).isEmpty();
    }

    @Test
    void vehicleSpeedPreservedDuringTransfer() {
        RoadNetwork network = buildFourWaySignalNetwork(Set.of("r_north_in", "r_south_in"));
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        Vehicle v = buildVehicle("v1", 249.0, 10.0, inLane, 0);

        manager.processTransfers(network, 1);

        // After transfer, find the vehicle and check speed
        assertThat(inLane.getVehiclesView()).isEmpty();
        // Vehicle starts at buffer position (intersection edge), speed preserved
        assertThat(v.getPosition()).isGreaterThanOrEqualTo(0.0);
        assertThat(v.getSpeed()).isEqualTo(10.0);
    }

    @Test
    void deadlockDetectedAndResolved() {
        // Use NONE intersection type so canEnter is governed by right-of-way, not lights
        // Actually, we need all approaches blocked. Use SIGNAL with ALL_RED phase.
        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r_north_in", buildRoad("r_north_in", 250, 400, 50, 400, 300, "n_north", "n_center"));
        roads.put("r_south_in", buildRoad("r_south_in", 250, 400, 550, 400, 300, "n_south", "n_center"));
        roads.put("r_north_out", buildRoad("r_north_out", 250, 400, 300, 420, 50, "n_center", "n_north_exit"));
        roads.put("r_south_out", buildRoad("r_south_out", 250, 400, 300, 380, 550, "n_center", "n_south_exit"));

        // ALL_RED phase — nobody can enter
        List<TrafficLightPhase> phases = new ArrayList<>();
        phases.add(TrafficLightPhase.builder()
            .greenRoadIds(Set.of())
            .durationMs(999999)
            .type(TrafficLightPhase.PhaseType.ALL_RED)
            .build());
        TrafficLight light = TrafficLight.builder()
            .intersectionId("n_center")
            .phases(phases)
            .currentPhaseIndex(0)
            .phaseElapsedMs(0)
            .build();

        Intersection ixtn = Intersection.builder()
            .id("n_center")
            .type(IntersectionType.SIGNAL)
            .inboundRoadIds(new ArrayList<>(List.of("r_north_in", "r_south_in")))
            .outboundRoadIds(new ArrayList<>(List.of("r_north_out", "r_south_out")))
            .connectedRoadIds(new ArrayList<>(roads.keySet()))
            .trafficLight(light)
            .build();

        Map<String, Intersection> intersections = new LinkedHashMap<>();
        intersections.put(ixtn.getId(), ixtn);

        RoadNetwork network = RoadNetwork.builder()
            .id("deadlock-test")
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();

        // Place waiting vehicles on both inbound roads (near end, speed ~0)
        Lane northLane = roads.get("r_north_in").getLanes().get(0);
        Lane southLane = roads.get("r_south_in").getLanes().get(0);
        buildVehicle("v_north", 244.0, 0.0, northLane, 0);
        buildVehicle("v_south", 244.0, 0.0, southLane, 100);

        // Tick 201 times without any normal transfer — deadlock should trigger
        for (int tick = 1; tick <= 201; tick++) {
            manager.processTransfers(network, tick);
        }

        // After deadlock resolution, one vehicle should have been force-advanced to an outbound road
        int totalInbound = northLane.getVehiclesView().size() + southLane.getVehiclesView().size();
        Lane northOut = roads.get("r_north_out").getLanes().get(0);
        Lane southOut = roads.get("r_south_out").getLanes().get(0);
        int totalOutbound = northOut.getVehiclesView().size() + southOut.getVehiclesView().size();

        assertThat(totalOutbound).isGreaterThanOrEqualTo(1);
        assertThat(totalInbound + totalOutbound).isEqualTo(2); // no vehicles lost
    }

    // ---- Roundabout tests ----

    /**
     * Builds a 4-way ROUNDABOUT network with ring roads.
     * Ring nodes: n_ring_n, n_ring_e, n_ring_s, n_ring_w
     * Ring roads: r_ring_nw, r_ring_ws, r_ring_se, r_ring_en (counterclockwise)
     */
    private RoadNetwork buildFourWayRoundaboutNetwork(int capacity) {
        Map<String, Road> roads = new LinkedHashMap<>();
        // Approach roads → ring nodes
        roads.put("r_north_in", buildRoad("r_north_in", 200, 393, 50, 393, 272, "n_north", "n_ring_n"));
        roads.put("r_south_in", buildRoad("r_south_in", 200, 407, 550, 407, 328, "n_south", "n_ring_s"));
        roads.put("r_west_in",  buildRoad("r_west_in",  300, 50, 307, 372, 307, "n_west", "n_ring_w"));
        roads.put("r_east_in",  buildRoad("r_east_in",  300, 750, 293, 428, 293, "n_east", "n_ring_e"));
        // Departure roads ← ring nodes
        roads.put("r_north_out", buildRoad("r_north_out", 200, 407, 272, 407, 50, "n_ring_n", "n_north_exit"));
        roads.put("r_south_out", buildRoad("r_south_out", 200, 393, 328, 393, 550, "n_ring_s", "n_south_exit"));
        roads.put("r_west_out",  buildRoad("r_west_out",  300, 372, 293, 50, 293, "n_ring_w", "n_west_exit"));
        roads.put("r_east_out",  buildRoad("r_east_out",  300, 428, 307, 750, 307, "n_ring_e", "n_east_exit"));
        // Ring segments (counterclockwise: N→W→S→E→N)
        roads.put("r_ring_nw", buildRoad("r_ring_nw", 22, 400, 272, 372, 300, "n_ring_n", "n_ring_w"));
        roads.put("r_ring_ws", buildRoad("r_ring_ws", 22, 372, 300, 400, 328, "n_ring_w", "n_ring_s"));
        roads.put("r_ring_se", buildRoad("r_ring_se", 22, 400, 328, 428, 300, "n_ring_s", "n_ring_e"));
        roads.put("r_ring_en", buildRoad("r_ring_en", 22, 428, 300, 400, 272, "n_ring_e", "n_ring_n"));

        // Ring node intersections
        Map<String, Intersection> intersections = new LinkedHashMap<>();
        for (var entry : Map.of(
                "n_ring_n", new String[]{"r_north_in", "r_ring_en", "r_north_out", "r_ring_nw"},
                "n_ring_w", new String[]{"r_west_in", "r_ring_nw", "r_west_out", "r_ring_ws"},
                "n_ring_s", new String[]{"r_south_in", "r_ring_ws", "r_south_out", "r_ring_se"},
                "n_ring_e", new String[]{"r_east_in", "r_ring_se", "r_east_out", "r_ring_en"}
        ).entrySet()) {
            String nodeId = entry.getKey();
            String[] roadIds = entry.getValue();
            Intersection ixtn = Intersection.builder()
                .id(nodeId)
                .type(IntersectionType.ROUNDABOUT)
                .roundaboutCapacity(capacity)
                .intersectionSize(12)
                .inboundRoadIds(new ArrayList<>(List.of(roadIds[0], roadIds[1])))
                .outboundRoadIds(new ArrayList<>(List.of(roadIds[2], roadIds[3])))
                .connectedRoadIds(new ArrayList<>(List.of(roadIds)))
                .build();
            intersections.put(nodeId, ixtn);
        }

        return RoadNetwork.builder()
            .id("roundabout-test")
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();
    }

    @Test
    void roundaboutTransferWhenEmpty() {
        RoadNetwork network = buildFourWayRoundaboutNetwork(8);
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        buildVehicle("v1", 249.0, 5.0, inLane, 0);
        manager.processTransfers(network, 1);

        // Empty roundabout — vehicle should pass through
        assertThat(inLane.getVehiclesView()).isEmpty();
    }

    @Test
    void roundaboutYieldToCirculatingTraffic() {
        RoadNetwork network = buildFourWayRoundaboutNetwork(8);
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);

        // Place vehicle wanting to enter from north
        buildVehicle("v_entering", 199.0, 5.0, inLane, 0);

        // Place circulating vehicle on the ring road arriving at n_ring_n (from east)
        Road ringEn = network.getRoads().get("r_ring_en");
        Lane ringLane = ringEn.getLanes().get(0);
        buildVehicle("v_circulating", 18.0, 5.0, ringLane, 0);

        Map<String, Double> stopLines = manager.computeStopLines(network);

        // North approach should be blocked (yield to ring traffic)
        assertThat(stopLines).containsKey("r_north_in-lane0");
    }

    @Test
    void roundaboutEntryGatingAtHighCapacity() {
        // Capacity = 4, threshold = 80% = 3.2, so 4 vehicles blocks entry
        RoadNetwork network = buildFourWayRoundaboutNetwork(4);

        // Fill ring roads with vehicles (simulating high occupancy)
        for (String ringId : List.of("r_ring_nw", "r_ring_ws", "r_ring_se", "r_ring_en")) {
            Road ringRoad = network.getRoads().get(ringId);
            Lane ringLane = ringRoad.getLanes().get(0);
            buildVehicle("occ_" + ringId, 10.0, 3.0, ringLane, 0);
        }

        // Verify occupancy count at n_ring_n (has r_ring_en inbound and r_ring_nw outbound)
        Intersection ixtn = network.getIntersections().get("n_ring_n");
        int occupancy = manager.countRoundaboutOccupancy(ixtn, network);
        assertThat(occupancy).isGreaterThanOrEqualTo(2); // r_ring_en + r_ring_nw vehicles

        // Try to enter from north
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);
        buildVehicle("v_entering", 199.0, 5.0, inLane, 0);

        Map<String, Double> stopLines = manager.computeStopLines(network);

        // Should be blocked — ring road has circulating traffic (yield rule)
        assertThat(stopLines).containsKey("r_north_in-lane0");
    }

    @Test
    void roundaboutAllowsEntryBelowCapacity() {
        RoadNetwork network = buildFourWayRoundaboutNetwork(8);

        // No ring vehicles — empty roundabout
        Road northIn = network.getRoads().get("r_north_in");
        Lane inLane = northIn.getLanes().get(0);
        buildVehicle("v_entering", 199.0, 5.0, inLane, 0);

        manager.processTransfers(network, 1);

        // Should pass through (empty roundabout — enters ring road)
        assertThat(inLane.getVehiclesView()).isEmpty();
    }

    @Test
    void roundaboutDeadlockResolves() {
        // Very low capacity
        RoadNetwork network = buildFourWayRoundaboutNetwork(1);

        // Fill a ring road to trigger yield/gating
        Road ringNw = network.getRoads().get("r_ring_nw");
        buildVehicle("occ1", 10.0, 0.0, ringNw.getLanes().get(0), 0);

        // Place waiting vehicles on two approach roads
        Lane northLane = network.getRoads().get("r_north_in").getLanes().get(0);
        Lane westLane = network.getRoads().get("r_west_in").getLanes().get(0);
        buildVehicle("v_north", 194.0, 0.0, northLane, 0);
        buildVehicle("v_west", 294.0, 0.0, westLane, 100);

        // Tick 201 times — deadlock watchdog should trigger at some ring node
        for (int tick = 1; tick <= 201; tick++) {
            manager.processTransfers(network, tick);
        }

        // Count all vehicles across all roads
        int totalVehicles = 0;
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                totalVehicles += lane.getVehicleCount();
            }
        }

        // All 3 vehicles should still exist (no vehicles lost)
        assertThat(totalVehicles).isEqualTo(3);
    }

    @Test
    void rightOfWayYieldsToVehicleFromRight() {
        // Build a PRIORITY intersection with 2 perpendicular inbound roads
        Map<String, Road> roads = new LinkedHashMap<>();
        // "Our" road approaches from west (left to right)
        roads.put("r_west_in", buildRoad("r_west_in", 200, 0, 200, 200, 200, "n_west", "n_center"));
        // Road from "right" approaches from south (bottom to top) — 90 degrees clockwise from west
        roads.put("r_south_in", buildRoad("r_south_in", 200, 200, 400, 200, 200, "n_south", "n_center"));
        // Outbound road
        roads.put("r_east_out", buildRoad("r_east_out", 200, 200, 200, 400, 200, "n_center", "n_east_exit"));

        Intersection ixtn = Intersection.builder()
            .id("n_center")
            .type(IntersectionType.PRIORITY)
            .inboundRoadIds(new ArrayList<>(List.of("r_west_in", "r_south_in")))
            .outboundRoadIds(new ArrayList<>(List.of("r_east_out")))
            .connectedRoadIds(new ArrayList<>(roads.keySet()))
            .build();

        Map<String, Intersection> intersections = new LinkedHashMap<>();
        intersections.put(ixtn.getId(), ixtn);

        RoadNetwork network = RoadNetwork.builder()
            .id("priority-test")
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();

        // Place vehicle approaching from our road (west)
        Lane westLane = roads.get("r_west_in").getLanes().get(0);
        buildVehicle("v_west", 199.0, 5.0, westLane, 0);

        // Place vehicle approaching from the right (south) — near the intersection
        Lane southLane = roads.get("r_south_in").getLanes().get(0);
        buildVehicle("v_south", 195.0, 5.0, southLane, 0);

        // computeStopLines should block the west road (vehicle from right has priority)
        Map<String, Double> stopLines = manager.computeStopLines(network);

        assertThat(stopLines).containsKey("r_west_in-lane0");
    }

    // ---- Merge lane targeting tests ----

    /**
     * Helper: build a road with a given number of lanes.
     */
    private static Road buildMultiLaneRoad(String id, double length, int laneCount,
                                           String fromNode, String toNode) {
        Road road = Road.builder()
            .id(id).name(id).length(length).speedLimit(13.9)
            .startX(0).startY(0).endX(length).endY(0)
            .fromNodeId(fromNode).toNodeId(toNode)
            .lanes(new ArrayList<>())
            .build();
        for (int i = 0; i < laneCount; i++) {
            Lane lane = Lane.builder()
                .id(id + "-lane" + i).laneIndex(i).road(road)
                .length(length).maxSpeed(13.9).active(true)
                .build();
            road.getLanes().add(lane);
        }
        return road;
    }

    /**
     * Builds a highway-merge-like network: 1-lane ramp + 2-lane main highway merging
     * at a PRIORITY intersection, with a 2-lane outbound road.
     */
    private RoadNetwork buildMergeNetwork() {
        Map<String, Road> roads = new LinkedHashMap<>();
        // 2-lane main highway before merge
        roads.put("main_before", buildMultiLaneRoad("main_before", 300, 2, "main_entry", "merge_point"));
        // 1-lane on-ramp
        roads.put("ramp", buildMultiLaneRoad("ramp", 200, 1, "ramp_entry", "merge_point"));
        // 2-lane highway after merge
        roads.put("main_after", buildMultiLaneRoad("main_after", 700, 2, "merge_point", "main_exit"));

        Intersection ixtn = Intersection.builder()
            .id("merge_point")
            .type(IntersectionType.PRIORITY)
            .inboundRoadIds(new ArrayList<>(List.of("main_before", "ramp")))
            .outboundRoadIds(new ArrayList<>(List.of("main_after")))
            .connectedRoadIds(new ArrayList<>(roads.keySet()))
            .build();

        Map<String, Intersection> intersections = new LinkedHashMap<>();
        intersections.put(ixtn.getId(), ixtn);

        return RoadNetwork.builder()
            .id("merge-test")
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();
    }

    @Test
    void mergeRampVehicleTargetsLane0OfOutboundRoad() {
        // Scenario: 1-lane ramp merges onto 2-lane main_after
        // Vehicle from ramp should land on lane 0 (rightmost), NOT a random lane
        RoadNetwork network = buildMergeNetwork();

        Lane rampLane = network.getRoads().get("ramp").getLanes().get(0);
        Lane mainAfterLane0 = network.getRoads().get("main_after").getLanes().get(0);
        Lane mainAfterLane1 = network.getRoads().get("main_after").getLanes().get(1);

        // Place ramp vehicle past threshold
        buildVehicle("v_ramp", 199.0, 5.0, rampLane, 0);

        manager.processTransfers(network, 1);

        // Vehicle should be transferred off ramp
        assertThat(rampLane.getVehiclesView()).isEmpty();

        // Vehicle must land on lane 0 specifically (merge onto rightmost)
        assertThat(mainAfterLane0.getVehiclesView()).hasSize(1);
        assertThat(mainAfterLane0.getVehiclesView().get(0).getId()).isEqualTo("v_ramp");
        assertThat(mainAfterLane1.getVehiclesView()).isEmpty();
    }

    @Test
    void equalLaneTransferRemainsRandom() {
        // Scenario: 2-lane main_before onto 2-lane main_after
        // Equal lane counts -> random lane selection (both lanes should be reachable)
        buildMergeNetwork();

        // Run many transfers and collect which lanes received vehicles
        Set<Integer> lanesUsed = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            // Fresh network each time
            RoadNetwork net = buildMergeNetwork();
            Lane inLane0 = net.getRoads().get("main_before").getLanes().get(0);
            buildVehicle("v" + i, 299.0, 5.0, inLane0, i);

            manager.processTransfers(net, i + 1);

            Road mainAfter = net.getRoads().get("main_after");
            for (int li = 0; li < mainAfter.getLanes().size(); li++) {
                if (!mainAfter.getLanes().get(li).getVehiclesView().isEmpty()) {
                    lanesUsed.add(li);
                }
            }
        }
        // Both lanes should have been used across 50 trials (random distribution)
        assertThat(lanesUsed).contains(0, 1);
    }
}
