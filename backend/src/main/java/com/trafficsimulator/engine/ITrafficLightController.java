package com.trafficsimulator.engine;

import com.trafficsimulator.model.RoadNetwork;

public interface ITrafficLightController {
    void tick(double dt, RoadNetwork network);
}
