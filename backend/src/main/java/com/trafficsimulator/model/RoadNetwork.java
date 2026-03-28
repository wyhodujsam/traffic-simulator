package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class RoadNetwork {
    private String id;                                // map scenario id

    @Builder.Default
    private Map<String, Road> roads = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Intersection> intersections = new LinkedHashMap<>();

    private List<SpawnPoint> spawnPoints;
    private List<DespawnPoint> despawnPoints;
}
