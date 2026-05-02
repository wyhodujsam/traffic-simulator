package com.trafficsimulator.engine;

import java.util.Comparator;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

import lombok.RequiredArgsConstructor;

/**
 * Implements D-12: slow-leader pulse. At {@code tick=cfg.tick}, the "vehicle 0" (min spawnedAt
 * with lex tie-break by id, per RESEARCH.md Pattern 6) clamps to {@code cfg.targetSpeed} for
 * {@code cfg.durationTicks} ticks, then resumes normal IDM behaviour.
 *
 * <p>Window is half-open: {@code [start, start + durationTicks)} — the vehicle resumes normal
 * behaviour exactly at {@code start + durationTicks}.
 *
 * <p>Vehicle-0 lookup is cached per-tick. The cached value is invalidated automatically on tick
 * advance. Tests can call {@link #clearCache()} to reset between scenarios.
 *
 * <p>Cost model: O(N) once per tick (where N is the network's current vehicle count) when vehicle 0
 * is recomputed; O(1) for every subsequent {@code getActiveV0} call within the same tick.
 */
@Component
@RequiredArgsConstructor
public class PerturbationManager implements IPerturbationManager {

    private final SimulationEngine engine;

    private long cachedTick = -1L;
    private String cachedVehicle0Id;

    @Override
    public Double getActiveV0(Vehicle vehicle, long currentTick) {
        RoadNetwork network = engine.getRoadNetwork();
        if (network == null) {
            return null;
        }
        MapConfig.PerturbationConfig cfg = network.getPerturbation();
        if (cfg == null) {
            return null;
        }

        long start = cfg.getTick();
        long end = start + cfg.getDurationTicks(); // exclusive end
        if (currentTick < start || currentTick >= end) {
            return null;
        }

        String v0Id = resolveVehicle0Id(network, currentTick);
        if (v0Id == null || !v0Id.equals(vehicle.getId())) {
            return null;
        }

        return cfg.getTargetSpeed();
    }

    /**
     * Re-computes "vehicle 0" once per tick. Returns the id of the vehicle with the smallest
     * {@code spawnedAt}, lex tie-broken by {@code id}; {@code null} if the network has no vehicles.
     */
    private String resolveVehicle0Id(RoadNetwork network, long currentTick) {
        if (currentTick == cachedTick) {
            return cachedVehicle0Id;
        }
        Optional<Vehicle> v0 =
                network.getRoads().values().stream()
                        .flatMap(r -> r.getLanes().stream())
                        .flatMap(l -> l.getVehiclesView().stream())
                        .min(
                                Comparator.comparingLong(Vehicle::getSpawnedAt)
                                        .thenComparing(Vehicle::getId));
        cachedTick = currentTick;
        cachedVehicle0Id = v0.map(Vehicle::getId).orElse(null);
        return cachedVehicle0Id;
    }

    /** Test-only seam — clears cache so tests can reset between runs. */
    public void clearCache() {
        cachedTick = -1L;
        cachedVehicle0Id = null;
    }
}
