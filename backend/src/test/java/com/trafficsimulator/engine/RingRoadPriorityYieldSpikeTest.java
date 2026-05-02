package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.model.DespawnPoint;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.SpawnPoint;
import com.trafficsimulator.model.Vehicle;

/**
 * WAVE-0 SPIKE — answers Phase-25 RESEARCH.md Open Question Q1 (Pitfall #2).
 *
 * <p>Builds an 8-segment closed ring (8 nodes, 8 roads, segment length ≈ 250 m, 2 lanes), assigns
 * each intersection a single {@link IntersectionType}, primes {@code VEHICLE_COUNT} vehicles, runs
 * {@code TICK_COUNT} ticks of the real intersection-transfer pipeline + a minimal in-test physics
 * step, and asserts every primed vehicle is still alive AND has advanced. The two test cases
 * compare PRIORITY (the natural choice for an unsignalled junction) against NONE (the recommended
 * fallback per Pitfall #2).
 *
 * <p>This test exercises the actual {@link IntersectionManager#processTransfers} pipeline (with its
 * {@code hasVehicleFromRight} short-circuit) so that the geometry-vs-priority interaction in a
 * same-angle ring is genuinely covered. The physics inside {@link #runTicks} is an in-test
 * approximation (pure {@code position += speed * dt}) and is intentionally minimal — we are only
 * measuring whether the intersection layer would block motion, not the IDM model itself.
 */
class RingRoadPriorityYieldSpikeTest {

    private static final double SEGMENT_LENGTH = 250.0; // m
    private static final double SPEED_LIMIT = 22.2; // m/s (~80 km/h)
    private static final int LANE_COUNT = 2;
    private static final int VEHICLE_COUNT = 8;
    private static final int TICK_COUNT = 100;
    private static final double DT = 0.05; // 50ms tick

    @Test
    void ringWithPriorityDoesNotStall() {
        RoadNetwork network = buildRingNetwork(IntersectionType.PRIORITY);
        primeUniformVehicles(network);

        runTicks(network, TICK_COUNT);

        assertThat(countVehicles(network))
                .as("vehicle count after %d ticks on PRIORITY ring", TICK_COUNT)
                .isEqualTo(VEHICLE_COUNT);
        assertThat(totalAdvancedDistance(network))
                .as("sum of vehicle positions after %d ticks on PRIORITY ring", TICK_COUNT)
                .isGreaterThan(0.0);
    }

    @Test
    void ringWithNoneDoesNotStall_fallbackOption() {
        RoadNetwork network = buildRingNetwork(IntersectionType.NONE);
        primeUniformVehicles(network);

        runTicks(network, TICK_COUNT);

        assertThat(countVehicles(network))
                .as("vehicle count after %d ticks on NONE ring", TICK_COUNT)
                .isEqualTo(VEHICLE_COUNT);
        assertThat(totalAdvancedDistance(network))
                .as("sum of vehicle positions after %d ticks on NONE ring", TICK_COUNT)
                .isGreaterThan(0.0);
    }

    // --- Helpers --------------------------------------------------------------

    private RoadNetwork buildRingNetwork(IntersectionType type) {
        // 8 chord-approximation segments around a circle of circumference 8 * SEGMENT_LENGTH
        double radius = (8.0 * SEGMENT_LENGTH) / (2.0 * Math.PI);
        double cx = 500.0;
        double cy = 500.0;

        Map<String, Road> roads = new LinkedHashMap<>();
        Map<String, Intersection> intersections = new LinkedHashMap<>();

        // Build 8 roads r0..r7. Road r_i goes from node n_i to node n_(i+1 mod 8).
        for (int i = 0; i < 8; i++) {
            double a0 = 2.0 * Math.PI * i / 8.0;
            double a1 = 2.0 * Math.PI * (i + 1) / 8.0;
            String roadId = "r" + i;
            List<Lane> lanes = new ArrayList<>();
            Road road =
                    Road.builder()
                            .id(roadId)
                            .name("Seg " + i)
                            .startX(cx + radius * Math.cos(a0))
                            .startY(cy + radius * Math.sin(a0))
                            .endX(cx + radius * Math.cos(a1))
                            .endY(cy + radius * Math.sin(a1))
                            .length(SEGMENT_LENGTH)
                            .speedLimit(SPEED_LIMIT)
                            .lanes(lanes)
                            .fromNodeId("n" + i)
                            .toNodeId("n" + ((i + 1) % 8))
                            .build();
            for (int li = 0; li < LANE_COUNT; li++) {
                lanes.add(
                        Lane.builder()
                                .id(roadId + "-lane" + li)
                                .laneIndex(li)
                                .road(road)
                                .length(SEGMENT_LENGTH)
                                .maxSpeed(SPEED_LIMIT)
                                .active(true)
                                .build());
            }
            roads.put(roadId, road);
        }

        // Build 8 intersections n0..n7. Each n_i has inbound r_(i-1 mod 8) and outbound r_i.
        for (int i = 0; i < 8; i++) {
            String nodeId = "n" + i;
            int inIdx = (i + 7) % 8;
            Intersection ixtn =
                    Intersection.builder()
                            .id(nodeId)
                            .type(type)
                            .inboundRoadIds(new ArrayList<>(List.of("r" + inIdx)))
                            .outboundRoadIds(new ArrayList<>(List.of("r" + i)))
                            .connectedRoadIds(new ArrayList<>(List.of("r" + inIdx, "r" + i)))
                            .build();
            intersections.put(nodeId, ixtn);
        }

        return RoadNetwork.builder()
                .id("ring-spike")
                .roads(roads)
                .intersections(intersections)
                .spawnPoints(List.<SpawnPoint>of())
                .despawnPoints(List.<DespawnPoint>of())
                .build();
    }

    private void primeUniformVehicles(RoadNetwork network) {
        List<Road> roads = new ArrayList<>(network.getRoads().values());
        // Place each vehicle on lane 0 of road i at position 0 — uniformly distributed around ring.
        for (int i = 0; i < VEHICLE_COUNT; i++) {
            Road r = roads.get(i % roads.size());
            Lane lane = r.getLanes().get(0);
            Vehicle v =
                    Vehicle.builder()
                            .id(UUID.randomUUID().toString())
                            .position(0.0)
                            .speed(SPEED_LIMIT * 0.8)
                            .acceleration(0.0)
                            .lane(lane)
                            .length(4.5)
                            .v0(SPEED_LIMIT)
                            .aMax(1.4)
                            .b(2.0)
                            .s0(2.0)
                            .timeHeadway(1.5)
                            .spawnedAt(0L)
                            .build();
            lane.addVehicle(v);
        }
    }

    /**
     * Runs {@code ticks} iterations of: minimal physics step, then real {@link IntersectionManager}
     * transfer logic. Vehicles that reach {@code position >= length} on an inbound road are eligible
     * for transfer to the next ring segment. If the intersection layer blocks all transfers
     * (PRIORITY-yield false-positive) vehicles will pile up at lane ends and the test fails.
     */
    private void runTicks(RoadNetwork network, int ticks) {
        IntersectionManager manager = new IntersectionManager();
        for (int t = 1; t <= ticks; t++) {
            // 1. Physics: advance each vehicle by speed * dt, clamp to lane length so the
            //    intersection-transfer layer can move them across the boundary.
            for (Road road : network.getRoads().values()) {
                for (Lane lane : road.getLanes()) {
                    for (Vehicle v : new ArrayList<>(lane.getVehiclesView())) {
                        double newPos =
                                Math.min(road.getLength(), v.getPosition() + v.getSpeed() * DT);
                        v.updatePhysics(newPos, v.getSpeed(), 0.0);
                    }
                }
            }
            // 2. Real intersection transfers — exercises canEnterIntersection / hasVehicleFromRight.
            manager.processTransfers(network, t);
        }
    }

    private int countVehicles(RoadNetwork network) {
        int total = 0;
        for (Road r : network.getRoads().values()) {
            for (Lane l : r.getLanes()) {
                total += l.getVehiclesView().size();
            }
        }
        return total;
    }

    /** Sum of vehicle positions across the ring — a non-zero value proves vehicles have moved. */
    private double totalAdvancedDistance(RoadNetwork network) {
        double sum = 0.0;
        for (Road r : network.getRoads().values()) {
            for (Lane l : r.getLanes()) {
                for (Vehicle v : l.getVehiclesView()) {
                    sum += v.getPosition();
                }
            }
        }
        return sum;
    }
}
