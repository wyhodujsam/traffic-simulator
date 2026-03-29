package com.trafficsimulator.engine;

import com.trafficsimulator.model.RoadNetwork;

public interface IVehicleSpawner {
    void tick(double dt, RoadNetwork network, long currentTick);
    void despawnVehicles(RoadNetwork network);
    int getThroughput();
    void setVehiclesPerSecond(double rate);
    void reset();
}
