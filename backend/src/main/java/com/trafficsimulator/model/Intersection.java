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
    private List<String> inboundRoadIds = new ArrayList<>();

    @Builder.Default
    private List<String> outboundRoadIds = new ArrayList<>();

    private TrafficLight trafficLight;
}
