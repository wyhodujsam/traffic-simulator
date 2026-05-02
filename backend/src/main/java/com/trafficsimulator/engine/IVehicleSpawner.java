package com.trafficsimulator.engine;

import com.trafficsimulator.model.RoadNetwork;

public interface IVehicleSpawner {
    void tick(double dt, RoadNetwork network, long currentTick);

    /**
     * Despawns vehicles that have reached the end of an exit road. Records each despawn against
     * {@code currentTick} so the rolling throughput window remains deterministic across runs of
     * the same seed (Phase 25 Plan 01, DET-01 precondition).
     */
    void despawnVehicles(RoadNetwork network, long currentTick);

    /**
     * Returns vehicles despawned in the last 60 simulated seconds, evaluated as of the supplied
     * {@code currentTick}. Evicts stale entries strictly older than {@code currentTick - 60 *
     * TICKS_PER_SEC} (i.e. entries equal to the cutoff tick are retained).
     */
    int getThroughput(long currentTick);

    void setVehiclesPerSecond(double rate);

    void reset();
}
