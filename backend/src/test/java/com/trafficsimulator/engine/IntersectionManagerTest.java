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
        assertThat(stopLines).containsKey("r_west_in-lane0");
        assertThat(stopLines).containsKey("r_east_in-lane0");
    }

    @Test
    void noStopLinesForGreenLight() {
        // GREEN for all 4 inbound roads
        RoadNetwork network = buildFourWaySignalNetwork(
            Set.of("r_north_in", "r_south_in", "r_west_in", "r_east_in"));

        Map<String, Double> stopLines = manager.computeStopLines(network);

        // No red-light stop lines (box-blocking may still apply if outbound empty check fails,
        // but with empty outbound lanes, all should pass)
        assertThat(stopLines).doesNotContainKey("r_north_in-lane0");
        assertThat(stopLines).doesNotContainKey("r_south_in-lane0");
        assertThat(stopLines).doesNotContainKey("r_west_in-lane0");
        assertThat(stopLines).doesNotContainKey("r_east_in-lane0");
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

        // Vehicle should be on one of the outbound roads at position 0
        boolean foundOnOutbound = false;
        for (String outId : List.of("r_south_out", "r_west_out", "r_east_out")) {
            Road outRoad = network.getRoads().get(outId);
            if (!outRoad.getLanes().get(0).getVehiclesView().isEmpty()) {
                Vehicle transferred = outRoad.getLanes().get(0).getVehiclesView().get(0);
                assertThat(transferred.getId()).isEqualTo("v1");
                assertThat(transferred.getPosition()).isEqualTo(0.0);
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
        // The vehicle object itself should retain speed (it's mutated in place)
        assertThat(v.getPosition()).isEqualTo(0.0);
        // Speed is NOT explicitly set during transfer, so it retains original value
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
}
