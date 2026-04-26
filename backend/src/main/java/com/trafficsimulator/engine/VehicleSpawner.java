package com.trafficsimulator.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.trafficsimulator.model.DespawnPoint;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.SpawnPoint;
import com.trafficsimulator.model.Vehicle;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class VehicleSpawner implements IVehicleSpawner {

    // IDM base parameters
    private static final double V0_BASE = 33.3; // m/s (~120 km/h)
    private static final double A_BASE = 1.4; // m/s²
    private static final double B_BASE = 2.0; // m/s²
    private static final double S0 = 2.0; // metres (not randomised)
    private static final double T_BASE = 1.5; // seconds
    private static final double DEFAULT_VEHICLE_LENGTH = 4.5; // metres
    private static final double MIN_SAFE_GAP = S0 + DEFAULT_VEHICLE_LENGTH; // 6.5m

    /**
     * Tick frequency assumed by the rolling throughput window. Matches {@code
     * @Scheduled(fixedRate = 50)} on TickEmitter — 20 ticks per simulated second. Used to convert
     * the existing 60-second window semantics to a tick-keyed cutoff for determinism (Phase 25
     * Plan 01, DET-01 precondition).
     */
    private static final int TICKS_PER_SEC = 20;

    /** 60-second rolling window expressed in ticks (= 1200 ticks @ 20Hz). */
    private static final long THROUGHPUT_WINDOW_TICKS = 60L * TICKS_PER_SEC;

    private final Deque<Long> despawnTicks = new ArrayDeque<>();
    private double spawnAccumulator;

    @Getter @Setter private double vehiclesPerSecond = 1.0;

    private int spawnPointIndex;

    /**
     * Called each tick. Accumulates spawn budget and spawns vehicles when budget exceeds 1.0. Uses
     * round-robin spawn point selection.
     */
    @Override
    public void tick(double dt, RoadNetwork network, long currentTick) {
        spawnAccumulator += dt * vehiclesPerSecond;

        List<SpawnPoint> spawnPoints = network.getSpawnPoints();
        if (spawnPoints.isEmpty()) {
            return;
        }

        boolean allBlocked = false;
        while (spawnAccumulator >= 1.0 && !allBlocked) {
            spawnAccumulator -= 1.0;
            if (!trySpawnOne(network, spawnPoints, currentTick)) {
                // All spawn points blocked — defer budget to next tick
                spawnAccumulator += 1.0;
                allBlocked = true;
            }
        }
    }

    /**
     * Attempts to spawn one vehicle using round-robin spawn point selection. Returns true if a
     * vehicle was spawned, false if all spawn points are blocked.
     */
    private boolean trySpawnOne(
            RoadNetwork network, List<SpawnPoint> spawnPoints, long currentTick) {
        int maxAttempts = spawnPoints.size();
        for (int i = 0; i < maxAttempts; i++) {
            SpawnPoint sp = spawnPoints.get(spawnPointIndex % spawnPoints.size());
            spawnPointIndex++;

            if (trySpawnAtPoint(sp, network, currentTick)) {
                return true;
            }
        }
        log.debug("All spawn points blocked, deferring spawn");
        return false;
    }

    private boolean trySpawnAtPoint(SpawnPoint sp, RoadNetwork network, long currentTick) {
        Road road = network.getRoads().get(sp.roadId());
        if (road == null) {
            return false;
        }

        Lane lane = road.getLanes().get(sp.laneIndex());
        if (!lane.isActive()) {
            return false;
        }

        if (isSpawnPositionClear(lane, sp.position())) {
            Vehicle vehicle = createVehicle(lane, sp.position(), currentTick);
            lane.addVehicle(vehicle);
            log.debug(
                    "Spawned vehicle {} on lane {} at position {}",
                    vehicle.getId(),
                    lane.getId(),
                    sp.position());
            return true;
        }
        return false;
    }

    private boolean isSpawnPositionClear(Lane lane, double spawnPosition) {
        return lane.getVehiclesView().stream()
                .noneMatch(v -> Math.abs(v.getPosition() - spawnPosition) < MIN_SAFE_GAP);
    }

    private Vehicle createVehicle(Lane lane, double position, long currentTick) {
        // Spawn at 50% of lane max speed to avoid immediate braking cascade at entry
        double initialSpeed = 0.5 * lane.getMaxSpeed();
        return Vehicle.builder()
                .id(UUID.randomUUID().toString())
                .position(position)
                .speed(initialSpeed)
                .acceleration(0.0)
                .lane(lane)
                .length(DEFAULT_VEHICLE_LENGTH)
                .v0(vary(V0_BASE))
                .aMax(vary(A_BASE))
                .b(vary(B_BASE))
                .s0(S0)
                .timeHeadway(vary(T_BASE))
                .spawnedAt(currentTick)
                .build();
    }

    /**
     * Applies ±20% uniform random noise to a base parameter value. Returns value in range [base *
     * 0.8, base * 1.2].
     */
    private double vary(double base) {
        return base * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4);
    }

    /**
     * Despawns vehicles that have reached or passed the end of their lane. Only despawns on roads
     * ending at EXIT nodes (roads with despawn points). Called at the end of each tick after
     * physics update.
     *
     * @param currentTick the current simulation tick number; recorded against each despawn so the
     *     rolling throughput window stays simulation-relative (deterministic across runs).
     */
    @Override
    public void despawnVehicles(RoadNetwork network, long currentTick) {
        // Build set of road IDs that are exit roads (have despawn points)
        Set<String> exitRoadIds =
                network.getDespawnPoints().stream()
                        .map(DespawnPoint::roadId)
                        .collect(Collectors.toSet());

        for (Road road : network.getRoads().values()) {
            if (!exitRoadIds.contains(road.getId())) {
                continue; // skip non-exit roads
            }
            for (Lane lane : road.getLanes()) {
                lane.removeVehiclesIf(
                        v -> {
                            if (v.getPosition() >= lane.getLength()) {
                                despawnTicks.addLast(currentTick);
                                log.debug(
                                        "Vehicle {} despawned at position {} (lane length {})",
                                        v.getId(),
                                        v.getPosition(),
                                        lane.getLength());
                                return true;
                            }
                            return false;
                        });
            }
        }
    }

    /**
     * Returns vehicles despawned in the last 60 simulated seconds (= {@code
     * THROUGHPUT_WINDOW_TICKS} ticks at 20 Hz). Evicts stale entries strictly older than the
     * cutoff (entries equal to the cutoff tick are retained).
     *
     * <p>Tick-keyed instead of wall-clock keyed so two runs of the same seed produce the same
     * throughput readings — DET-01 precondition (Phase 25 Plan 01).
     *
     * @param currentTick the simulation's current tick number
     */
    @Override
    public int getThroughput(long currentTick) {
        long cutoff = currentTick - THROUGHPUT_WINDOW_TICKS;
        while (!despawnTicks.isEmpty() && despawnTicks.peekFirst() < cutoff) {
            despawnTicks.pollFirst();
        }
        return despawnTicks.size();
    }

    @Override
    public void reset() {
        spawnAccumulator = 0.0;
        spawnPointIndex = 0;
        despawnTicks.clear();
    }
}
