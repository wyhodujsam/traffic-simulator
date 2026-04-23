package com.trafficsimulator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Shared, pure-function OSM → MapConfig conversion helpers.
 *
 * <p>Phase 18 ({@link OsmPipelineService}, Overpass-based) and Phase 23
 * ({@code GraphHopperOsmService}) both consume these helpers so the two pipelines produce
 * byte-identical geometry/speed/lane/signal/endpoint output for the same bbox. Any divergence in
 * the A/B comparison therefore comes from OSM parsing differences, not from helper drift.
 *
 * <p>Utility class — all members are {@code public static}, constructor is private.
 */
public final class OsmConversionUtils {

    private static final Logger log = LoggerFactory.getLogger(OsmConversionUtils.class);

    // -------------------------------------------------------------------------
    // Projection + layout constants (promoted verbatim from OsmPipelineService)
    // -------------------------------------------------------------------------

    /** Canvas width (backend coords) used by the WGS84 → pixel projection. */
    public static final double CANVAS_W = 1600.0;

    /** Canvas height (backend coords) used by the WGS84 → pixel projection. */
    public static final double CANVAS_H = 1200.0;

    /** Padding (backend coords) on all sides of the projected area. */
    public static final double CANVAS_PADDING = 50.0;

    /** Minimum lane count (MapValidator enforces laneCount 1–4). */
    public static final int MIN_LANE_COUNT = 1;

    /** Maximum lane count (MapValidator enforces laneCount 1–4). */
    public static final int MAX_LANE_COUNT = 4;

    /** Shorter ways are filtered out. */
    public static final double MIN_ROAD_LENGTH_M = 1.0;

    /** Default duration of a generated signal phase (ms). */
    public static final long DEFAULT_SIGNAL_PHASE_MS = 30_000L;

    /**
     * Lane width in backend coords — frontend renders LANE_WIDTH_PX = 14 * RENDER_SCALE.
     * Exposed so bidirectional offset logic stays consistent across converters.
     */
    public static final double LANE_WIDTH_BACKEND = 14.0;

    private OsmConversionUtils() {
        // utility class — no instances
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /**
     * Projects a longitude onto the canvas X axis. {@code lon == west} maps to
     * {@link #CANVAS_PADDING}; {@code lon == east} maps to {@code CANVAS_W - CANVAS_PADDING}.
     */
    public static double lonToX(double lon, double west, double east) {
        double usable = CANVAS_W - 2 * CANVAS_PADDING;
        return CANVAS_PADDING + (lon - west) / (east - west) * usable;
    }

    /**
     * Projects a latitude onto the canvas Y axis. Y is inverted: {@code lat == north} maps to
     * {@link #CANVAS_PADDING}; {@code lat == south} maps to {@code CANVAS_H - CANVAS_PADDING}.
     */
    public static double latToY(double lat, double south, double north) {
        double usable = CANVAS_H - 2 * CANVAS_PADDING;
        return CANVAS_PADDING + (north - lat) / (north - south) * usable;
    }

    /** Great-circle distance between two WGS84 points (metres). */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadius = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Sum-of-segments way length. Each element of {@code latLonPairs} is a {@code {lat, lon}}
     * coordinate. Any null entries are skipped (no segment contribution). The caller supplies
     * already-resolved coordinates so this helper stays independent of the caller's node schema
     * (Phase 18 uses {@code OsmNode}; Phase 23 uses GraphHopper's base graph).
     */
    public static double computeWayLength(List<double[]> latLonPairs) {
        if (latLonPairs == null || latLonPairs.size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i + 1 < latLonPairs.size(); i++) {
            double[] a = latLonPairs.get(i);
            double[] b = latLonPairs.get(i + 1);
            if (a != null && b != null) {
                total += haversineMeters(a[0], a[1], b[0], b[1]);
            }
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Domain helpers
    // -------------------------------------------------------------------------

    /**
     * Default speed limit (m/s) for a given OSM {@code highway} tag. Unknown values fall back to
     * the residential default (8.3 m/s).
     */
    public static double speedLimitForHighway(String highway) {
        return switch (highway) {
            case "motorway" -> 36.1;
            case "trunk" -> 27.8;
            case "primary" -> 19.4;
            case "secondary" -> 16.7;
            case "tertiary" -> 13.9;
            case "unclassified" -> 11.1;
            case "living_street" -> 2.8;
            default -> 8.3; // residential + fallback
        };
    }

    /**
     * Lane count for a way given its tags and highway class. Explicit {@code lanes} tag wins if
     * numeric (clamped to {@link #MIN_LANE_COUNT}–{@link #MAX_LANE_COUNT}); otherwise falls back
     * to highway-class defaults.
     */
    public static int laneCountForWay(Map<String, String> tags, String highway) {
        String lanesTag = tags == null ? null : tags.get("lanes");
        if (lanesTag != null) {
            try {
                int parsed = Integer.parseInt(lanesTag.trim());
                return Math.max(MIN_LANE_COUNT, Math.min(MAX_LANE_COUNT, parsed));
            } catch (NumberFormatException ignored) {
                // fall through to highway default
            }
        }
        return switch (highway) {
            case "motorway", "trunk", "primary", "secondary" -> 2;
            default -> 1;
        };
    }

    /**
     * Builds a {@link MapConfig.RoadConfig} from fully-formed node IDs (caller prepends its own
     * prefix such as {@code "osm-"} or {@code "gh-"}).
     */
    public static MapConfig.RoadConfig buildRoadConfig(
            String roadId,
            String fromNodeIdFull,
            String toNodeIdFull,
            double length,
            double speedLimit,
            int laneCount,
            double lateralOffset) {
        MapConfig.RoadConfig road = new MapConfig.RoadConfig();
        road.setId(roadId);
        road.setFromNodeId(fromNodeIdFull);
        road.setToNodeId(toNodeIdFull);
        road.setLength(length);
        road.setSpeedLimit(speedLimit);
        road.setLaneCount(laneCount);
        road.setLateralOffset(lateralOffset);
        return road;
    }

    /**
     * Builds two default signal phases for an intersection at {@code fullNodeId}.
     *
     * <p>The inbound roads (those with {@code toNodeId == fullNodeId}) are split into two groups
     * in round-robin order; phase 1 greens group 1, phase 2 greens group 2 (or repeats group 1 if
     * there is no second group). Each phase has {@link #DEFAULT_SIGNAL_PHASE_MS} duration and type
     * {@code "GREEN"}.
     */
    public static List<MapConfig.SignalPhaseConfig> buildDefaultSignalPhases(
            String fullNodeId, List<MapConfig.RoadConfig> roads) {

        List<String> inboundGroup1 = new ArrayList<>();
        List<String> inboundGroup2 = new ArrayList<>();
        boolean first = true;
        for (MapConfig.RoadConfig road : roads) {
            if (road.getToNodeId().equals(fullNodeId)) {
                if (first) {
                    inboundGroup1.add(road.getId());
                } else {
                    inboundGroup2.add(road.getId());
                }
                first = !first;
            }
        }

        List<MapConfig.SignalPhaseConfig> phases = new ArrayList<>();

        if (!inboundGroup1.isEmpty()) {
            MapConfig.SignalPhaseConfig phase1 = new MapConfig.SignalPhaseConfig();
            phase1.setGreenRoadIds(inboundGroup1);
            phase1.setDurationMs(DEFAULT_SIGNAL_PHASE_MS);
            phase1.setType("GREEN");
            phases.add(phase1);
        }

        List<String> group2 = inboundGroup2.isEmpty() ? inboundGroup1 : inboundGroup2;
        MapConfig.SignalPhaseConfig phase2 = new MapConfig.SignalPhaseConfig();
        phase2.setGreenRoadIds(group2);
        phase2.setDurationMs(DEFAULT_SIGNAL_PHASE_MS);
        phase2.setType("GREEN");
        phases.add(phase2);

        return phases;
    }

    /**
     * Adds one spawn point per lane for every road whose {@code fromNodeId} equals
     * {@code fullNodeId}. Position is 0.0 (start of road).
     */
    public static void collectSpawnPoints(
            String fullNodeId,
            List<MapConfig.RoadConfig> roads,
            List<MapConfig.SpawnPointConfig> sink) {
        for (MapConfig.RoadConfig road : roads) {
            if (road.getFromNodeId().equals(fullNodeId)) {
                for (int lane = 0; lane < road.getLaneCount(); lane++) {
                    MapConfig.SpawnPointConfig sp = new MapConfig.SpawnPointConfig();
                    sp.setRoadId(road.getId());
                    sp.setLaneIndex(lane);
                    sp.setPosition(0.0);
                    sink.add(sp);
                }
            }
        }
    }

    /**
     * Adds one despawn point per lane for every road whose {@code toNodeId} equals
     * {@code fullNodeId}. Position is the road length (end of road).
     */
    public static void collectDespawnPoints(
            String fullNodeId,
            List<MapConfig.RoadConfig> roads,
            List<MapConfig.DespawnPointConfig> sink) {
        for (MapConfig.RoadConfig road : roads) {
            if (road.getToNodeId().equals(fullNodeId)) {
                for (int lane = 0; lane < road.getLaneCount(); lane++) {
                    MapConfig.DespawnPointConfig dp = new MapConfig.DespawnPointConfig();
                    dp.setRoadId(road.getId());
                    dp.setLaneIndex(lane);
                    dp.setPosition(road.getLength());
                    sink.add(dp);
                }
            }
        }
    }

    /**
     * Assembles the final {@link MapConfig}. Empty intersections/spawn/despawn lists are stored as
     * {@code null} (matching Phase 18 behaviour), NOT as empty lists. The bbox-derived id prefix
     * ({@code osm-bbox-...}) is unchanged across converters: the A/B comparison is by identity on
     * the bbox coordinates, not the converter of origin.
     */
    public static MapConfig assembleMapConfig(
            BboxRequest bbox,
            List<MapConfig.NodeConfig> nodes,
            List<MapConfig.RoadConfig> roads,
            List<MapConfig.IntersectionConfig> intersections,
            List<MapConfig.SpawnPointConfig> spawnPoints,
            List<MapConfig.DespawnPointConfig> despawnPoints) {

        String bboxId =
                String.format(
                        "osm-bbox-%.4f-%.4f-%.4f-%.4f",
                        bbox.south(), bbox.west(), bbox.north(), bbox.east());

        MapConfig config = new MapConfig();
        config.setId(bboxId);
        config.setName("OSM Import");
        config.setDefaultSpawnRate(1.0);
        config.setNodes(nodes);
        config.setRoads(roads);
        config.setIntersections(intersections.isEmpty() ? null : intersections);
        config.setSpawnPoints(spawnPoints.isEmpty() ? null : spawnPoints);
        config.setDespawnPoints(despawnPoints.isEmpty() ? null : despawnPoints);

        log.info(
                "Converted OSM data: {} nodes, {} roads, {} intersections",
                nodes.size(),
                roads.size(),
                intersections.size());

        return config;
    }
}
