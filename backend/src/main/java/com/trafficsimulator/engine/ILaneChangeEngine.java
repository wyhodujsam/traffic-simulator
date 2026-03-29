package com.trafficsimulator.engine;

import com.trafficsimulator.model.RoadNetwork;

public interface ILaneChangeEngine {
    void tick(RoadNetwork network, long currentTick);
}
