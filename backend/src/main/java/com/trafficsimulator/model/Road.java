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
}
