package com.trafficsimulator.engine;

import com.trafficsimulator.model.Vehicle;

/**
 * Plan 25-03 Task 2 — D-12 perturbation hook contract. {@link PhysicsEngine#tick} consults this on
 * every per-vehicle integration step; non-null result overrides the IDM desired speed for one tick.
 */
public interface IPerturbationManager {
    /**
     * @return override desired-speed (m/s) for this vehicle on this tick, or {@code null} if no
     *     override applies (no perturbation configured, tick outside window, or vehicle is not the
     *     deterministic "vehicle 0" — min spawnedAt with lex tie-break by id).
     */
    Double getActiveV0(Vehicle vehicle, long currentTick);
}
