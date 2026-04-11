package com.trafficsimulator.engine;

import java.util.Map;

import com.trafficsimulator.model.RoadNetwork;

public interface IIntersectionManager {
    Map<String, Double> computeStopLines(RoadNetwork network);

    void processTransfers(RoadNetwork network, long currentTick);
}
