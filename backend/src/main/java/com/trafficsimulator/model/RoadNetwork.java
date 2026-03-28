package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level road network graph.
 * Full implementation provided by Plan 2.1.
 */
@Data
@Builder
public class RoadNetwork {

    @Builder.Default
    private List<Road> roads = new ArrayList<>();
}
