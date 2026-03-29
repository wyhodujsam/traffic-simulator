package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Road {
    private String id;           // matches JSON config (e.g., "r1")
    private String name;         // human-readable label
    private List<Lane> lanes;    // index-ordered (lane 0 = rightmost)
    private double length;       // metres
    private double speedLimit;   // m/s
    private double startX;       // canvas/world X of road start node
    private double startY;       // canvas/world Y of road start node
    private double endX;         // canvas/world X of road end node
    private double endY;         // canvas/world Y of road end node
    private String fromNodeId;   // connected node ID
    private String toNodeId;     // connected node ID

    /**
     * Returns the active lane to the left (higher index) of the given lane, or null.
     */
    public Lane getLeftNeighbor(Lane lane) {
        int idx = lane.getLaneIndex() + 1;
        if (idx < lanes.size() && lanes.get(idx).isActive()) return lanes.get(idx);
        return null;
    }

    /**
     * Returns the active lane to the right (lower index) of the given lane, or null.
     */
    public Lane getRightNeighbor(Lane lane) {
        int idx = lane.getLaneIndex() - 1;
        if (idx >= 0 && lanes.get(idx).isActive()) return lanes.get(idx);
        return null;
    }
}
