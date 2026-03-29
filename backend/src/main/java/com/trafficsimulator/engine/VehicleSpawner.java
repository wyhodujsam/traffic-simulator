package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Component
@Slf4j
public class VehicleSpawner {

    // IDM base parameters
    private static final double V0_BASE = 33.3;   // m/s (~120 km/h)
    private static final double A_BASE  = 1.4;    // m/s²
    private static final double B_BASE  = 2.0;    // m/s²
    private static final double S0      = 2.0;    // metres (not randomised)
    private static final double T_BASE  = 1.5;    // seconds
    private static final double DEFAULT_VEHICLE_LENGTH = 4.5; // metres
    private static final double MIN_SAFE_GAP = S0 + DEFAULT_VEHICLE_LENGTH; // 6.5m
    private static final long THROUGHPUT_WINDOW_MS = 60_000;

    private final Deque<Long> despawnTimestamps = new ArrayDeque<>();
    private double spawnAccumulator = 0.0;

    @Getter @Setter
    private double vehiclesPerSecond = 1.0;

    private int spawnPointIndex = 0;

    /**
     * Called each tick. Accumulates spawn budget and spawns vehicles when
     * budget exceeds 1.0. Uses round-robin spawn point selection.
     */
    public void tick(double dt, RoadNetwork network, long currentTick) {
        spawnAccumulator += dt * vehiclesPerSecond;

        List<SpawnPoint> spawnPoints = network.getSpawnPoints();
        if (spawnPoints.isEmpty()) return;

        while (spawnAccumulator >= 1.0) {
            spawnAccumulator -= 1.0;
            boolean spawned = trySpawnOne(network, spawnPoints, currentTick);
            if (!spawned) {
                // All spawn points blocked — defer budget to next tick
                spawnAccumulator += 1.0;
                break;
            }
        }
    }

    /**
     * Attempts to spawn one vehicle using round-robin spawn point selection.
     * Returns true if a vehicle was spawned, false if all spawn points are blocked.
     */
    private boolean trySpawnOne(RoadNetwork network, List<SpawnPoint> spawnPoints, long currentTick) {
        int maxAttempts = spawnPoints.size();
        for (int i = 0; i < maxAttempts; i++) {
            SpawnPoint sp = spawnPoints.get(spawnPointIndex % spawnPoints.size());
            spawnPointIndex++;

            Road road = network.getRoads().get(sp.roadId());
            if (road == null) continue;

            Lane lane = road.getLanes().get(sp.laneIndex());
            if (!lane.isActive()) continue;

            if (isSpawnPositionClear(lane, sp.position())) {
                Vehicle vehicle = createVehicle(lane, sp.position(), currentTick);
                lane.getVehicles().add(vehicle);
                log.debug("Spawned vehicle {} on lane {} at position {}",
                    vehicle.getId(), lane.getId(), sp.position());
                return true;
            }
        }
        log.debug("All spawn points blocked, deferring spawn");
        return false;
    }

    private boolean isSpawnPositionClear(Lane lane, double spawnPosition) {
        return lane.getVehicles().stream()
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
            .T(vary(T_BASE))
            .spawnedAt(currentTick)
            .build();
    }

    /**
     * Applies ±20% uniform random noise to a base parameter value.
     * Returns value in range [base * 0.8, base * 1.2].
     */
    private double vary(double base) {
        return base * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4);
    }

    /**
     * Despawns vehicles that have reached or passed the end of their lane.
     * Only despawns on roads ending at EXIT nodes (roads with despawn points).
     * Called at the end of each tick after physics update.
     */
    public void despawnVehicles(RoadNetwork network) {
        // Build set of road IDs that are exit roads (have despawn points)
        Set<String> exitRoadIds = network.getDespawnPoints().stream()
            .map(DespawnPoint::roadId)
            .collect(Collectors.toSet());

        for (Road road : network.getRoads().values()) {
            if (!exitRoadIds.contains(road.getId())) continue;  // skip non-exit roads
            for (Lane lane : road.getLanes()) {
                lane.getVehicles().removeIf(v -> {
                    if (v.getPosition() >= lane.getLength()) {
                        despawnTimestamps.addLast(System.currentTimeMillis());
                        log.debug("Vehicle {} despawned at position {} (lane length {})",
                            v.getId(), v.getPosition(), lane.getLength());
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /**
     * Returns vehicles despawned in the last 60 seconds.
     * Evicts stale entries older than the window.
     */
    public int getThroughput() {
        long cutoff = System.currentTimeMillis() - THROUGHPUT_WINDOW_MS;
        while (!despawnTimestamps.isEmpty() && despawnTimestamps.peekFirst() < cutoff) {
            despawnTimestamps.pollFirst();
        }
        return despawnTimestamps.size();
    }

    public void reset() {
        spawnAccumulator = 0.0;
        spawnPointIndex = 0;
        despawnTimestamps.clear();
    }
}
