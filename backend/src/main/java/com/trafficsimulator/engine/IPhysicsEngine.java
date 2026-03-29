package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Vehicle;

public interface IPhysicsEngine {
    void tick(Lane lane, double dt);
    void tick(Lane lane, double dt, double stopLinePosition);
    double computeAcceleration(Vehicle v, double leaderPos, double leaderSpeed,
                               double leaderLength, boolean hasLeader);
    double computeFreeFlowAcceleration(Vehicle vehicle);
}
