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

    // TrafficLight trafficLight; — null in Phase 2, added in Phase 8
}
