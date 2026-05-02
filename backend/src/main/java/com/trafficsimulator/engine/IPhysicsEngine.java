package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Vehicle;

public interface IPhysicsEngine {
    void tick(Lane lane, double dt);

    void tick(Lane lane, double dt, double stopLinePosition);

    /**
     * Plan 25-03 D-12 — perturbation-aware tick. When {@code perturbationManager} is non-null and
     * returns a non-null override for a given vehicle/tick pair, that vehicle's IDM desired speed
     * is replaced for that integration step. Pass {@code null}/{@code 0L} for the
     * non-perturbed callsites (tests, ad-hoc invocations).
     */
    void tick(
            Lane lane,
            double dt,
            double stopLinePosition,
            IPerturbationManager perturbationManager,
            long currentTick);

    double computeAcceleration(
            Vehicle v,
            double leaderPos,
            double leaderSpeed,
            double leaderLength,
            boolean hasLeader);

    double computeFreeFlowAcceleration(Vehicle vehicle);
}
