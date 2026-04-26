package com.trafficsimulator.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
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

    /**
     * Optional master RNG seed (D-01). When present, takes precedence over auto-generated seed but
     * yields to STOMP {@code Start.seed} per resolution chain {@code command > json > auto}. Read by
     * {@link com.trafficsimulator.engine.SimulationEngine#resolveSeedAndStart(Long)} via {@link
     * com.trafficsimulator.model.RoadNetwork#getSeed()} after MapLoader propagation.
     */
    @JsonProperty("seed")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long seed;

    /**
     * Optional perturbation block (D-12 — slow-leader pulse). When present, drives {@link
     * com.trafficsimulator.engine.PerturbationManager#getActiveV0} during the configured tick
     * window, clamping the deterministic "vehicle 0" (min spawnedAt, lex tie-break by id) to {@link
     * PerturbationConfig#getTargetSpeed()} for {@link PerturbationConfig#getDurationTicks()} ticks.
     */
    @JsonProperty("perturbation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PerturbationConfig perturbation;

    /**
     * Optional list of vehicles to prime onto the road network at load time (CONTEXT.md §Q2 —
     * replaces a separate PrimeScenario command). {@link
     * com.trafficsimulator.engine.CommandDispatcher} iterates this list after {@code
     * handleLoadMap}/{@code handleLoadConfig} and inserts vehicles via {@code Lane.addVehicle}.
     * Out-of-range road/lane references are silently skipped (T-25-IV-01 mitigation).
     */
    @JsonProperty("initialVehicles")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<InitialVehicleConfig> initialVehicles;

    /** D-12 perturbation schema. Values trusted (build artifact); see plan threat model T-25-04-A. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerturbationConfig {
        /** Tick at which perturbation begins (inclusive start). */
        private long tick;

        /** Vehicle index per D-12; 0 = "leftmost spawned vehicle" (min spawnedAt, lex tie-break). */
        private int vehicleIndex;

        /** Override desired speed (m/s) applied to the matched vehicle during the window. */
        private double targetSpeed;

        /** Duration of the override (ticks); window is [tick, tick + durationTicks). */
        private long durationTicks;
    }

    /** Initial-vehicle schema (CONTEXT.md §Q2). Primed by CommandDispatcher after map load. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitialVehicleConfig {
        private String roadId;
        private int laneIndex;
        private double position;
        private double speed;
    }

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
        private double lateralOffset; // perpendicular shift (backend coords) for rendering bidirectional pairs

        /** Phase 24: optional per-lane metadata from osm2streets. Null for Phase 18/23 output. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<LaneConfig> lanes;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaneConfig {
        /** One of: "driving", "parking", "cycling", "sidewalk". MVP set per 24-CONTEXT §7. */
        @JsonProperty("type")
        private String type;

        /** Lane width in metres, as reported by osm2streets LaneSpec.width. */
        @JsonProperty("width")
        private double width;

        /** One of: "forward", "backward", "both". */
        @JsonProperty("direction")
        private String direction;
    }
}
