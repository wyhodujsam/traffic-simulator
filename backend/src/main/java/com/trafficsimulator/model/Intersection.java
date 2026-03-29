package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class Intersection {
    private String id;
    private IntersectionType type;

    @Builder.Default
    private List<String> connectedRoadIds = new ArrayList<>();

    @Builder.Default
    private List<String> inboundRoadIds = new ArrayList<>();   // roads whose toNodeId = this intersection

    @Builder.Default
    private List<String> outboundRoadIds = new ArrayList<>();  // roads whose fromNodeId = this intersection

    @Builder.Default
    private double intersectionSize = 0;  // pixel radius of intersection box (0 = auto)

    private TrafficLight trafficLight;  // null for non-SIGNAL types
}
