package com.trafficsimulator.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches road data from the Overpass API and converts it into a {@link MapConfig} object suitable
 * for loading into the simulation engine.
 *
 * <p>Pure-function helpers (projection, speed/lane lookup, signal/endpoint assembly) live in
 * {@link OsmConversionUtils} so Phase 23's GraphHopper-based converter can reuse them with
 * IDENTICAL constants — A/B comparison fairness.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OsmPipelineService {

    private static final String HIGHWAY_TAG = "highway";
    private static final String OSM_ID_PREFIX = "osm-";

    private final RestClient overpassRestClient;
    private final ObjectMapper objectMapper;

    @Value("${osm.overpass.urls:https://overpass-api.de}")
    private List<String> overpassMirrors;

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

    /** Grouped parameters for creating road configs from a single OSM way. */
    record WayRoadParams(
            long wayId,
            long firstId,
            long lastId,
            double length,
            double speedLimit,
            int laneCount,
            boolean isOnewayForward,
            boolean isOnewayReverse) {}

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

        log.info("Fetching OSM data for bbox: {} (mirrors={})", bbox, overpassMirrors);
        String json = fetchFromMirrors(encoded);
        return convertOsmToMapConfig(json, bbox);
    }

    private String fetchFromMirrors(String encodedBody) {
        RestClientException lastError = null;
        for (String baseUrl : overpassMirrors) {
            String url = baseUrl.replaceAll("/+$", "") + "/api/interpreter";
            try {
                log.info("Overpass attempt: {}", url);
                return overpassRestClient
                        .post()
                        .uri(url)
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .body(encodedBody)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException e) {
                log.warn("Overpass mirror {} failed: {}", baseUrl, e.getMessage());
                lastError = e;
            }
        }
        throw new RestClientException(
                "All Overpass mirrors failed (" + overpassMirrors.size() + " tried)",
                lastError != null ? lastError : new IllegalStateException("no mirrors configured"));
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
        RoadResult roadResult = parseRoads(parsed.ways(), parsed.nodeMap());

        if (roadResult.roads().isEmpty()) {
            throw new IllegalStateException("No roads found in selected area");
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
                        roadResult.roads());

        EndpointResult endpoints = generateEndpoints(ixData.terminalNodeIds(), roadResult.roads());

        return OsmConversionUtils.assembleMapConfig(
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

        Set<Long> roundaboutNodeIds = new HashSet<>();
        for (OsmWay way : ways) {
            if ("roundabout".equals(way.tags().get("junction"))) {
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

    private RoadResult parseRoads(List<OsmWay> ways, Map<Long, OsmNode> nodeMap) {

        List<MapConfig.RoadConfig> roads = new ArrayList<>();
        Set<Long> usedEndpointNodeIds = new HashSet<>();

        for (OsmWay way : ways) {
            if (isWayTooShortOrUnresolvable(way, nodeMap)) {
                continue;
            }
            processWay(way, nodeMap, roads, usedEndpointNodeIds);
        }

        return new RoadResult(roads, usedEndpointNodeIds);
    }

    private boolean isWayTooShortOrUnresolvable(OsmWay way, Map<Long, OsmNode> nodeMap) {
        List<Long> nodeIds = way.nodeIds();
        long resolvableCount = nodeIds.stream().filter(nodeMap::containsKey).count();
        if (resolvableCount < 2) {
            return true;
        }
        double length = computeWayLength(nodeIds, nodeMap);
        return length < OsmConversionUtils.MIN_ROAD_LENGTH_M;
    }

    private void processWay(
            OsmWay way,
            Map<Long, OsmNode> nodeMap,
            List<MapConfig.RoadConfig> roads,
            Set<Long> usedEndpointNodeIds) {

        List<Long> nodeIds = way.nodeIds();
        long firstId = nodeIds.get(0);
        long lastId = nodeIds.get(nodeIds.size() - 1);

        boolean isRoundabout = "roundabout".equals(way.tags().get("junction"));
        String oneway = way.tags().get("oneway");
        boolean isOnewayForward = "yes".equals(oneway) || "true".equals(oneway) || isRoundabout;
        boolean isOnewayReverse = "-1".equals(oneway);

        String highway = way.tags().getOrDefault(HIGHWAY_TAG, "residential");
        double speedLimit = OsmConversionUtils.speedLimitForHighway(highway);
        int laneCount = OsmConversionUtils.laneCountForWay(way.tags(), highway);
        double length = computeWayLength(nodeIds, nodeMap);

        WayRoadParams params =
                new WayRoadParams(
                        way.id(),
                        firstId,
                        lastId,
                        length,
                        speedLimit,
                        laneCount,
                        isOnewayForward,
                        isOnewayReverse);
        addRoadsForWay(roads, params);
        usedEndpointNodeIds.add(firstId);
        usedEndpointNodeIds.add(lastId);
    }

    private void addRoadsForWay(List<MapConfig.RoadConfig> roads, WayRoadParams p) {
        if (p.isOnewayReverse()) {
            roads.add(
                    buildRoadConfig(
                            OSM_ID_PREFIX + p.wayId() + "-fwd",
                            p.lastId(),
                            p.firstId(),
                            p.length(),
                            p.speedLimit(),
                            p.laneCount(),
                            0.0));
        } else if (p.isOnewayForward()) {
            roads.add(
                    buildRoadConfig(
                            OSM_ID_PREFIX + p.wayId() + "-fwd",
                            p.firstId(),
                            p.lastId(),
                            p.length(),
                            p.speedLimit(),
                            p.laneCount(),
                            0.0));
        } else {
            // Bidirectional: shift each direction perpendicular to its OWN forward
            // (positive offset = right of driving direction → matches RHT layout).
            double offset = p.laneCount() * OsmConversionUtils.LANE_WIDTH_BACKEND / 2.0 + 1.0;
            roads.add(
                    buildRoadConfig(
                            OSM_ID_PREFIX + p.wayId() + "-fwd",
                            p.firstId(),
                            p.lastId(),
                            p.length(),
                            p.speedLimit(),
                            p.laneCount(),
                            offset));
            roads.add(
                    buildRoadConfig(
                            OSM_ID_PREFIX + p.wayId() + "-rev",
                            p.lastId(),
                            p.firstId(),
                            p.length(),
                            p.speedLimit(),
                            p.laneCount(),
                            offset));
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
            nodeConfig.setId(OSM_ID_PREFIX + nid);
            nodeConfig.setType(nodeType);
            nodeConfig.setX(OsmConversionUtils.lonToX(osmNode.lon(), bbox.west(), bbox.east()));
            nodeConfig.setY(OsmConversionUtils.latToY(osmNode.lat(), bbox.south(), bbox.north()));
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
                    roads.stream().anyMatch(r -> r.getFromNodeId().equals(OSM_ID_PREFIX + nid));
            return isFromNode ? "ENTRY" : "EXIT";
        }
        return "ENTRY"; // fallback
    }

    private List<MapConfig.IntersectionConfig> buildIntersectionConfigs(
            Set<Long> intersectionNodeIds,
            Set<Long> usedEndpointNodeIds,
            Map<Long, OsmNode> nodeMap,
            Set<Long> roundaboutNodeIds,
            List<MapConfig.RoadConfig> roads) {

        List<MapConfig.IntersectionConfig> intersections = new ArrayList<>();
        for (long nid : intersectionNodeIds) {
            if (!usedEndpointNodeIds.contains(nid)) {
                continue;
            }
            OsmNode osmNode = nodeMap.get(nid);

            MapConfig.IntersectionConfig ic = new MapConfig.IntersectionConfig();
            ic.setNodeId(OSM_ID_PREFIX + nid);

            if (osmNode != null && "traffic_signals".equals(osmNode.tags().get(HIGHWAY_TAG))) {
                ic.setType("SIGNAL");
                ic.setSignalPhases(
                        OsmConversionUtils.buildDefaultSignalPhases(OSM_ID_PREFIX + nid, roads));
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
            String nodeId = OSM_ID_PREFIX + nid;
            OsmConversionUtils.collectSpawnPoints(nodeId, roads, spawnPoints);
            OsmConversionUtils.collectDespawnPoints(nodeId, roads, despawnPoints);
        }

        return new EndpointResult(spawnPoints, despawnPoints);
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

    /**
     * Thin adapter: converts the local {@link OsmNode} map into the {@code List<double[]>}
     * schema expected by {@link OsmConversionUtils#computeWayLength(List)}. Kept here so the
     * Phase-18-specific {@code OsmNode} type doesn't leak into the shared utility.
     */
    private double computeWayLength(List<Long> nodeIds, Map<Long, OsmNode> nodeMap) {
        List<double[]> latLonPairs = new ArrayList<>(nodeIds.size());
        for (long id : nodeIds) {
            OsmNode n = nodeMap.get(id);
            latLonPairs.add(n == null ? null : new double[] {n.lat(), n.lon()});
        }
        return OsmConversionUtils.computeWayLength(latLonPairs);
    }

    /**
     * Thin wrapper preserving Phase 18's call shape ({@code long} node ids, {@code "osm-"}
     * prefix). Delegates to {@link OsmConversionUtils#buildRoadConfig} with fully-formed ids.
     */
    private MapConfig.RoadConfig buildRoadConfig(
            String id,
            long fromNodeId,
            long toNodeId,
            double length,
            double speedLimit,
            int laneCount,
            double lateralOffset) {
        return OsmConversionUtils.buildRoadConfig(
                id,
                OSM_ID_PREFIX + fromNodeId,
                OSM_ID_PREFIX + toNodeId,
                length,
                speedLimit,
                laneCount,
                lateralOffset);
    }
}
