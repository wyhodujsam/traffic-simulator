package com.trafficsimulator.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Intersection {
    private String id;
    private IntersectionType type;

    @Builder.Default private List<String> connectedRoadIds = new ArrayList<>();

    @Builder.Default
    private List<String> inboundRoadIds =
            new ArrayList<>(); // roads whose toNodeId = this intersection

    @Builder.Default
    private List<String> outboundRoadIds =
            new ArrayList<>(); // roads whose fromNodeId = this intersection

    @Builder.Default private double intersectionSize; // pixel radius of intersection box (0 = auto)

    @Builder.Default
    private int roundaboutCapacity = 8; // max vehicles in roundabout zone (for ROUNDABOUT type)

    private double centerX; // node X coordinate (for rendering intersections with no roads)
    private double centerY; // node Y coordinate

    private TrafficLight trafficLight; // null for non-SIGNAL types
}
