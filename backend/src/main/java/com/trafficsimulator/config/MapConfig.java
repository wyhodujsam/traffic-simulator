package com.trafficsimulator.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MapConfig {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("nodes")
    private List<NodeConfig> nodes;

    @JsonProperty("roads")
    private List<RoadConfig> roads;

    @JsonProperty("intersections")
    private List<IntersectionConfig> intersections;

    @JsonProperty("spawnPoints")
    private List<SpawnPointConfig> spawnPoints;

    @JsonProperty("despawnPoints")
    private List<DespawnPointConfig> despawnPoints;

    @JsonProperty("defaultSpawnRate")
    private double defaultSpawnRate = 1.0;

    @Data
    @NoArgsConstructor
    public static class NodeConfig {
        private String id;
        private String type; // "ENTRY", "EXIT", "INTERSECTION"
        private double x;
        private double y;
    }

    @Data
    @NoArgsConstructor
    public static class RoadConfig {
        private String id;
        private String name;
        private String fromNodeId;
        private String toNodeId;
        private double length;
        private double speedLimit;
        private int laneCount;
        private List<Integer> closedLanes; // lane indexes to close at load (optional)
    }

    @Data
    @NoArgsConstructor
    public static class IntersectionConfig {
        private String nodeId;
        private String type; // "SIGNAL", "ROUNDABOUT", "PRIORITY"
        private List<SignalPhaseConfig> signalPhases; // nullable, only for SIGNAL type
        private double intersectionSize; // pixel radius of intersection box (default 0 = auto)
        private int roundaboutCapacity = 8; // max vehicles in roundabout zone (for ROUNDABOUT type)
    }

    @Data
    @NoArgsConstructor
    public static class SignalPhaseConfig {
        private List<String> greenRoadIds;
        private long durationMs;
        private String type; // "GREEN", "YELLOW", "ALL_RED" — defaults to GREEN if null
    }

    @Data
    @NoArgsConstructor
    public static class SpawnPointConfig {
        private String roadId;
        private int laneIndex;
        private double position;
    }

    @Data
    @NoArgsConstructor
    public static class DespawnPointConfig {
        private String roadId;
        private int laneIndex;
        private double position;
    }
}
