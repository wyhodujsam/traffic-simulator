package com.trafficsimulator.engine;

import com.trafficsimulator.model.RoadNetwork;

import java.util.Map;

public interface IIntersectionManager {
    Map<String, Double> computeStopLines(RoadNetwork network);
    void processTransfers(RoadNetwork network, long currentTick);
}
