package com.trafficsimulator.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches road data from the Overpass API and converts it into a {@link MapConfig} object suitable
 * for loading into the simulation engine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OsmPipelineService {

    // Canvas dimensions for WGS84 → pixel coordinate projection
    private static final double CANVAS_W = 1600.0;
    private static final double CANVAS_H = 1200.0;
    private static final double CANVAS_PADDING = 50.0;

    // Lane count bounds (MapValidator enforces laneCount 1–4)
    private static final int MIN_LANE_COUNT = 1;
    private static final int MAX_LANE_COUNT = 4;

    // Minimum road length — shorter roads are filtered out
    private static final double MIN_ROAD_LENGTH_M = 1.0;

    // Default signal phase duration (ms)
    private static final long DEFAULT_SIGNAL_PHASE_MS = 30_000L;

    private static final String HIGHWAY_TAG = "highway";

    private final RestClient overpassRestClient;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Inner records for parsed OSM elements
    // -------------------------------------------------------------------------

    record OsmNode(long id, double lat, double lon, Map<String, String> tags) {}

    record OsmWay(long id, List<Long> nodeIds, Map<String, String> tags) {}

    /** Intermediate result of parsing raw OSM elements. */
    record ParsedOsmData(Map<Long, OsmNode> nodeMap, List<OsmWay> ways) {}

    /** Intermediate result of intersection detection. */
    record IntersectionData(
            Set<Long> intersectionNodeIds,
            Set<Long> terminalNodeIds,
            Set<Long> roundaboutNodeIds) {}

    /** Intermediate result of road generation. */
    record RoadResult(List<MapConfig.RoadConfig> roads, Set<Long> usedEndpointNodeIds) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches OSM road data for the given bounding box and converts it to a {@link MapConfig}.
     *
     * @param bbox bounding box in WGS84
     * @return populated MapConfig ready for loading into the simulation engine
     * @throws IllegalStateException if no roads are found in the area
     */
    public MapConfig fetchAndConvert(BboxRequest bbox) {
        String query = buildOverpassQuery(bbox);
        String encoded = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        log.info("Fetching OSM data for bbox: {}", bbox);
        String json =
                overpassRestClient
                        .post()
                        .uri("/api/interpreter")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .body(encoded)
                        .retrieve()
                        .body(String.class);

        return convertOsmToMapConfig(json, bbox);
    }

    // -------------------------------------------------------------------------
    // Package-private for unit testing
    // -------------------------------------------------------------------------

    /**
     * Converts a raw Overpass API JSON response into a {@link MapConfig}. Package-private to allow
     * direct testing without network calls.
     */
    MapConfig convertOsmToMapConfig(String json, BboxRequest bbox) {
        JsonNode elements = parseJsonElements(json);

        ParsedOsmData parsed = parseOsmElements(elements);
        Map<Long, Integer> nodeRefCount = countNodeReferences(parsed.ways());
        IntersectionData ixData =
                detectIntersections(parsed.ways(), parsed.nodeMap(), nodeRefCount);
        RoadResult roadResult = parseRoads(parsed.ways(), parsed.nodeMap(), nodeRefCount);

        if (roadResult.roads().isEmpty()) {
            throw new IllegalStateException("No roads found in selected area");
        }

        Set<String> roadIds = new HashSet<>();
        for (MapConfig.RoadConfig r : roadResult.roads()) {
            roadIds.add(r.getId());
        }

        List<MapConfig.NodeConfig> nodes =
                buildNodeConfigs(
                        roadResult.usedEndpointNodeIds(),
                        parsed.nodeMap(),
                        ixData.intersectionNodeIds(),
                        ixData.terminalNodeIds(),
                        roadResult.roads(),
                        bbox);

        List<MapConfig.IntersectionConfig> intersections =
                buildIntersectionConfigs(
                        ixData.intersectionNodeIds(),
                        roadResult.usedEndpointNodeIds(),
                        parsed.nodeMap(),
                        ixData.roundaboutNodeIds(),
                        roadResult.roads(),
                        roadIds);

        EndpointResult endpoints = generateEndpoints(ixData.terminalNodeIds(), roadResult.roads());

        return assembleMapConfig(
                bbox,
                nodes,
                roadResult.roads(),
                intersections,
                endpoints.spawnPoints(),
                endpoints.despawnPoints());
    }

    // -------------------------------------------------------------------------
    // Decomposed conversion steps
    // -------------------------------------------------------------------------

    private JsonNode parseJsonElements(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Overpass JSON: " + e.getMessage(), e);
        }
        return root.path("elements");
    }

    private ParsedOsmData parseOsmElements(JsonNode elements) {
        Map<Long, OsmNode> nodeMap = new HashMap<>();
        List<OsmWay> ways = new ArrayList<>();

        for (JsonNode el : elements) {
            String type = el.path("type").asText();
            if ("node".equals(type)) {
                long id = el.path("id").asLong();
                double lat = el.path("lat").asDouble();
                double lon = el.path("lon").asDouble();
                Map<String, String> tags = parseTags(el);
                nodeMap.put(id, new OsmNode(id, lat, lon, tags));
            } else if ("way".equals(type)) {
                long id = el.path("id").asLong();
                List<Long> nodeIds = new ArrayList<>();
                for (JsonNode nid : el.path("nodes")) {
                    nodeIds.add(nid.asLong());
                }
                Map<String, String> tags = parseTags(el);
                ways.add(new OsmWay(id, nodeIds, tags));
            }
        }
        return new ParsedOsmData(nodeMap, ways);
    }

    private Map<Long, Integer> countNodeReferences(List<OsmWay> ways) {
        Map<Long, Integer> nodeRefCount = new HashMap<>();
        for (OsmWay way : ways) {
            for (long nid : way.nodeIds()) {
                nodeRefCount.merge(nid, 1, Integer::sum);
            }
        }
        return nodeRefCount;
    }

    private IntersectionData detectIntersections(
            List<OsmWay> ways, Map<Long, OsmNode> nodeMap, Map<Long, Integer> nodeRefCount) {

        Set<Long> roundaboutWayIds = new HashSet<>();
        Set<Long> roundaboutNodeIds = new HashSet<>();
        for (OsmWay way : ways) {
            if ("roundabout".equals(way.tags().get("junction"))) {
                roundaboutWayIds.add(way.id());
                roundaboutNodeIds.addAll(way.nodeIds());
            }
        }

        Set<Long> intersectionNodeIds = new HashSet<>();
        for (Map.Entry<Long, Integer> entry : nodeRefCount.entrySet()) {
            long nid = entry.getKey();
            int count = entry.getValue();
            OsmNode node = nodeMap.get(nid);
            boolean isSignal =
                    node != null && "traffic_signals".equals(node.tags().get(HIGHWAY_TAG));
            if (count >= 2 || isSignal) {
                intersectionNodeIds.add(nid);
            }
        }

        Set<Long> terminalNodeIds = new HashSet<>();
        for (OsmWay way : ways) {
            if (way.nodeIds().size() < 2) {
                continue;
            }
            long first = way.nodeIds().get(0);
            long last = way.nodeIds().get(way.nodeIds().size() - 1);
            if (nodeRefCount.getOrDefault(first, 0) == 1) {
                terminalNodeIds.add(first);
            }
            if (nodeRefCount.getOrDefault(last, 0) == 1) {
                terminalNodeIds.add(last);
            }
        }

        return new IntersectionData(intersectionNodeIds, terminalNodeIds, roundaboutNodeIds);
    }

    private RoadResult parseRoads(
            List<OsmWay> ways, Map<Long, OsmNode> nodeMap, Map<Long, Integer> nodeRefCount) {

        List<MapConfig.RoadConfig> roads = new ArrayList<>();
        Set<Long> usedEndpointNodeIds = new HashSet<>();

        for (OsmWay way : ways) {
            List<Long> nodeIds = way.nodeIds();

            long resolvableCount = nodeIds.stream().filter(nodeMap::containsKey).count();
            if (resolvableCount < 2) {
                continue;
            }

            double length = computeWayLength(nodeIds, nodeMap);
            if (length < MIN_ROAD_LENGTH_M) {
                continue;
            }

            long firstId = nodeIds.get(0);
            long lastId = nodeIds.get(nodeIds.size() - 1);

            boolean isRoundabout = "roundabout".equals(way.tags().get("junction"));
            String oneway = way.tags().get("oneway");
            boolean isOnewayForward = "yes".equals(oneway) || "true".equals(oneway) || isRoundabout;
            boolean isOnewayReverse = "-1".equals(oneway);

            String highway = way.tags().getOrDefault(HIGHWAY_TAG, "residential");
            double speedLimit = speedLimitForHighway(highway);
            int laneCount = laneCountForWay(way.tags(), highway);

            addRoadsForWay(
                    roads,
                    way.id(),
                    firstId,
                    lastId,
                    length,
                    speedLimit,
                    laneCount,
                    isOnewayForward,
                    isOnewayReverse);
            usedEndpointNodeIds.add(firstId);
            usedEndpointNodeIds.add(lastId);
        }

        return new RoadResult(roads, usedEndpointNodeIds);
    }

    private void addRoadsForWay(
            List<MapConfig.RoadConfig> roads,
            long wayId,
            long firstId,
            long lastId,
            double length,
            double speedLimit,
            int laneCount,
            boolean isOnewayForward,
            boolean isOnewayReverse) {

        if (isOnewayReverse) {
            roads.add(
                    buildRoadConfig(
                            "osm-" + wayId + "-fwd",
                            lastId,
                            firstId,
                            length,
                            speedLimit,
                            laneCount));
        } else if (isOnewayForward) {
            roads.add(
                    buildRoadConfig(
                            "osm-" + wayId + "-fwd",
                            firstId,
                            lastId,
                            length,
                            speedLimit,
                            laneCount));
        } else {
            roads.add(
                    buildRoadConfig(
                            "osm-" + wayId + "-fwd",
                            firstId,
                            lastId,
                            length,
                            speedLimit,
                            laneCount));
            roads.add(
                    buildRoadConfig(
                            "osm-" + wayId + "-rev",
                            lastId,
                            firstId,
                            length,
                            speedLimit,
                            laneCount));
        }
    }

    private List<MapConfig.NodeConfig> buildNodeConfigs(
            Set<Long> usedEndpointNodeIds,
            Map<Long, OsmNode> nodeMap,
            Set<Long> intersectionNodeIds,
            Set<Long> terminalNodeIds,
            List<MapConfig.RoadConfig> roads,
            BboxRequest bbox) {

        List<MapConfig.NodeConfig> nodes = new ArrayList<>();
        for (long nid : usedEndpointNodeIds) {
            OsmNode osmNode = nodeMap.get(nid);
            if (osmNode == null) {
                continue;
            }

            String nodeType = determineNodeType(nid, intersectionNodeIds, terminalNodeIds, roads);

            MapConfig.NodeConfig nodeConfig = new MapConfig.NodeConfig();
            nodeConfig.setId("osm-" + nid);
            nodeConfig.setType(nodeType);
            nodeConfig.setX(lonToX(osmNode.lon(), bbox.west(), bbox.east()));
            nodeConfig.setY(latToY(osmNode.lat(), bbox.south(), bbox.north()));
            nodes.add(nodeConfig);
        }
        return nodes;
    }

    private String determineNodeType(
            long nid,
            Set<Long> intersectionNodeIds,
            Set<Long> terminalNodeIds,
            List<MapConfig.RoadConfig> roads) {

        if (intersectionNodeIds.contains(nid)) {
            return "INTERSECTION";
        }
        if (terminalNodeIds.contains(nid)) {
            boolean isFromNode =
                    roads.stream().anyMatch(r -> r.getFromNodeId().equals("osm-" + nid));
            return isFromNode ? "ENTRY" : "EXIT";
        }
        return "ENTRY"; // fallback
    }

    private List<MapConfig.IntersectionConfig> buildIntersectionConfigs(
            Set<Long> intersectionNodeIds,
            Set<Long> usedEndpointNodeIds,
            Map<Long, OsmNode> nodeMap,
            Set<Long> roundaboutNodeIds,
            List<MapConfig.RoadConfig> roads,
            Set<String> roadIds) {

        List<MapConfig.IntersectionConfig> intersections = new ArrayList<>();
        for (long nid : intersectionNodeIds) {
            if (!usedEndpointNodeIds.contains(nid)) {
                continue;
            }
            OsmNode osmNode = nodeMap.get(nid);

            MapConfig.IntersectionConfig ic = new MapConfig.IntersectionConfig();
            ic.setNodeId("osm-" + nid);

            if (osmNode != null && "traffic_signals".equals(osmNode.tags().get(HIGHWAY_TAG))) {
                ic.setType("SIGNAL");
                ic.setSignalPhases(buildDefaultSignalPhases(nid, roads));
            } else if (roundaboutNodeIds.contains(nid)) {
                ic.setType("ROUNDABOUT");
                ic.setRoundaboutCapacity(8);
            } else {
                ic.setType("PRIORITY");
            }
            intersections.add(ic);
        }
        return intersections;
    }

    /** Intermediate result of endpoint generation. */
    record EndpointResult(
            List<MapConfig.SpawnPointConfig> spawnPoints,
            List<MapConfig.DespawnPointConfig> despawnPoints) {}

    private EndpointResult generateEndpoints(
            Set<Long> terminalNodeIds, List<MapConfig.RoadConfig> roads) {

        List<MapConfig.SpawnPointConfig> spawnPoints = new ArrayList<>();
        List<MapConfig.DespawnPointConfig> despawnPoints = new ArrayList<>();

        for (long nid : terminalNodeIds) {
            String nodeId = "osm-" + nid;
            for (MapConfig.RoadConfig road : roads) {
                if (road.getFromNodeId().equals(nodeId)) {
                    for (int lane = 0; lane < road.getLaneCount(); lane++) {
                        MapConfig.SpawnPointConfig sp = new MapConfig.SpawnPointConfig();
                        sp.setRoadId(road.getId());
                        sp.setLaneIndex(lane);
                        sp.setPosition(0.0);
                        spawnPoints.add(sp);
                    }
                }
                if (road.getToNodeId().equals(nodeId)) {
                    for (int lane = 0; lane < road.getLaneCount(); lane++) {
                        MapConfig.DespawnPointConfig dp = new MapConfig.DespawnPointConfig();
                        dp.setRoadId(road.getId());
                        dp.setLaneIndex(lane);
                        dp.setPosition(road.getLength());
                        despawnPoints.add(dp);
                    }
                }
            }
        }

        return new EndpointResult(spawnPoints, despawnPoints);
    }

    private MapConfig assembleMapConfig(
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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildOverpassQuery(BboxRequest bbox) {
        return """
                [out:json][timeout:25];
                (
                  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$"](%f,%f,%f,%f);
                );
                out body;
                >;
                out skel qt;\
                """
                .formatted(bbox.south(), bbox.west(), bbox.north(), bbox.east());
    }

    private Map<String, String> parseTags(JsonNode element) {
        Map<String, String> tags = new HashMap<>();
        JsonNode tagsNode = element.path("tags");
        if (tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    private double computeWayLength(List<Long> nodeIds, Map<Long, OsmNode> nodeMap) {
        double total = 0.0;
        for (int i = 0; i + 1 < nodeIds.size(); i++) {
            OsmNode a = nodeMap.get(nodeIds.get(i));
            OsmNode b = nodeMap.get(nodeIds.get(i + 1));
            if (a != null && b != null) {
                total += haversineMeters(a.lat(), a.lon(), b.lat(), b.lon());
            }
        }
        return total;
    }

    private MapConfig.RoadConfig buildRoadConfig(
            String id,
            long fromNodeId,
            long toNodeId,
            double length,
            double speedLimit,
            int laneCount) {
        MapConfig.RoadConfig road = new MapConfig.RoadConfig();
        road.setId(id);
        road.setFromNodeId("osm-" + fromNodeId);
        road.setToNodeId("osm-" + toNodeId);
        road.setLength(length);
        road.setSpeedLimit(speedLimit);
        road.setLaneCount(laneCount);
        return road;
    }

    private double speedLimitForHighway(String highway) {
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

    private int laneCountForWay(Map<String, String> tags, String highway) {
        String lanesTag = tags.get("lanes");
        if (lanesTag != null) {
            try {
                int parsed = Integer.parseInt(lanesTag.trim());
                return Math.max(MIN_LANE_COUNT, Math.min(MAX_LANE_COUNT, parsed));
            } catch (NumberFormatException ignored) {
                // fall through to highway default
            }
        }
        // Highway-type defaults
        return switch (highway) {
            case "motorway", "trunk", "primary", "secondary" -> 2;
            default -> 1;
        };
    }

    private List<MapConfig.SignalPhaseConfig> buildDefaultSignalPhases(
            long nodeId, List<MapConfig.RoadConfig> roads) {
        String nid = "osm-" + nodeId;

        // Group inbound roads (roads where toNodeId == this node)
        List<String> inboundGroup1 = new ArrayList<>();
        List<String> inboundGroup2 = new ArrayList<>();
        boolean first = true;
        for (MapConfig.RoadConfig road : roads) {
            if (road.getToNodeId().equals(nid)) {
                if (first) {
                    inboundGroup1.add(road.getId());
                } else {
                    inboundGroup2.add(road.getId());
                }
                first = !first;
            }
        }

        List<MapConfig.SignalPhaseConfig> phases = new ArrayList<>();

        // Phase 1: first group green
        if (!inboundGroup1.isEmpty()) {
            MapConfig.SignalPhaseConfig phase1 = new MapConfig.SignalPhaseConfig();
            phase1.setGreenRoadIds(inboundGroup1);
            phase1.setDurationMs(DEFAULT_SIGNAL_PHASE_MS);
            phase1.setType("GREEN");
            phases.add(phase1);
        }

        // Phase 2: second group green (or repeat first if no second group)
        List<String> group2 = inboundGroup2.isEmpty() ? inboundGroup1 : inboundGroup2;
        MapConfig.SignalPhaseConfig phase2 = new MapConfig.SignalPhaseConfig();
        phase2.setGreenRoadIds(group2);
        phase2.setDurationMs(DEFAULT_SIGNAL_PHASE_MS);
        phase2.setType("GREEN");
        phases.add(phase2);

        return phases;
    }

    // -------------------------------------------------------------------------
    // Static geometry helpers
    // -------------------------------------------------------------------------

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double lonToX(double lon, double west, double east) {
        double usable = CANVAS_W - 2 * CANVAS_PADDING;
        return CANVAS_PADDING + (lon - west) / (east - west) * usable;
    }

    static double latToY(double lat, double south, double north) {
        double usable = CANVAS_H - 2 * CANVAS_PADDING;
        return CANVAS_PADDING + (north - lat) / (north - south) * usable;
    }
}
