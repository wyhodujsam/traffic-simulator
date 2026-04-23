package com.trafficsimulator.service;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.WaySegmentParser;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.util.PointList;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.dto.BboxRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 23 GraphHopper-based OSM → {@link MapConfig} converter. Uses
 * {@code com.graphhopper.reader.osm.WaySegmentParser} (Path B per 23-RESEARCH.md §3) to split ways
 * at tower nodes with better fidelity than Phase 18's heuristic.
 *
 * <p>Per request: builds a throwaway in-memory {@link BaseGraph} backed by a {@link RAMDirectory},
 * runs the parser once, extracts the edges + tags, and delegates projection / lane / signal /
 * endpoint / assembly logic to {@link OsmConversionUtils} — guaranteeing A/B fairness with Phase
 * 18's {@link OsmPipelineService}.
 *
 * <p>All node and road ids are prefixed with {@code "gh-"} so A/B comparison outputs can be
 * interleaved without collision.
 *
 * <p><b>Spring wiring:</b> marked {@code @Lazy} per 23-SPIKE {@code ## A7} — a failing
 * {@code @Service} bean aborts the Spring context, which would break Phase 18's {@code
 * /api/osm/fetch-roads} coexistence requirement. {@code @Lazy} defers construction until the bean
 * is first injected, so a classpath issue at boot does not take down the application.
 */
@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class GraphHopperOsmService implements OsmConverter {

    /** OSM highway tag values we consider drivable for simulation. */
    private static final Set<String> DRIVABLE_HIGHWAYS =
            Set.of(
                    "motorway",
                    "trunk",
                    "primary",
                    "secondary",
                    "tertiary",
                    "unclassified",
                    "residential",
                    "living_street");

    /** Way-tag keys preserved from {@link ReaderWay} into our immutable tag copy. */
    private static final Set<String> WAY_TAGS_OF_INTEREST =
            Set.of("highway", "oneway", "lanes", "maxspeed", "junction", "name", "ref");

    private static final String ID_PREFIX = "gh-";
    private static final String HIGHWAY_TAG = "highway";
    private static final String JUNCTION_TAG = "junction";
    private static final String ROUNDABOUT_VALUE = "roundabout";
    private static final String TRAFFIC_SIGNALS_VALUE = "traffic_signals";

    private final RestClient overpassRestClient;
    private final MapValidator mapValidator;

    @Value("${osm.overpass.urls:https://overpass-api.de}")
    private List<String> overpassMirrors;

    @Override
    public String converterName() {
        return "GraphHopper";
    }

    // -------------------------------------------------------------------------
    // Intermediate types
    // -------------------------------------------------------------------------

    /** Captured state for one segment produced by the WaySegmentParser EdgeHandler. */
    private record ParsedEdge(
            int from, int to, PointList geometry, Map<String, String> tags, long osmWayId) {}

    /** Groups derived per-tower classification sets. */
    private record TowerClassification(
            Set<Integer> allTowers,
            Map<Integer, Integer> degree,
            Set<Integer> roundaboutTowers,
            Set<Integer> intersectionTowers,
            Set<Integer> terminalTowers) {}

    // -------------------------------------------------------------------------
    // Public API — OsmConverter contract
    // -------------------------------------------------------------------------

    @Override
    public MapConfig fetchAndConvert(BboxRequest bbox) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("gh-osm-");
            Path osmFile = fetchOverpassXmlToTempFile(bbox, tempDir);
            return fetchAndConvert(osmFile.toFile(), bbox);
        } catch (IOException e) {
            throw new IllegalStateException("OSM fetch/IO failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    // -------------------------------------------------------------------------
    // Package-private test-friendly overload — takes a pre-fetched OSM file.
    // -------------------------------------------------------------------------

    /**
     * Converts a pre-fetched OSM XML file to a {@link MapConfig}. Bypasses HTTP — used by unit
     * tests with bundled fixtures.
     */
    MapConfig fetchAndConvert(File osmFile, BboxRequest bbox) {
        Directory dir = new RAMDirectory();
        try (BaseGraph graph = new BaseGraph.Builder(0).setDir(dir).create()) {
            List<ParsedEdge> edges = new ArrayList<>();
            Set<Integer> signalTowers = new HashSet<>();

            WaySegmentParser parser = buildWaySegmentParser(graph, dir, edges, signalTowers);
            parser.readOSM(osmFile);

            return convertToMapConfig(edges, signalTowers, graph.getNodeAccess(), bbox);
        }
    }

    // -------------------------------------------------------------------------
    // Overpass fetch (mirrors Phase 18 pattern, XML variant)
    // -------------------------------------------------------------------------

    private Path fetchOverpassXmlToTempFile(BboxRequest bbox, Path tempDir) throws IOException {
        String query = buildOverpassXmlQuery(bbox);
        String encoded = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        log.info("Fetching OSM data (XML) for bbox: {} (mirrors={})", bbox, overpassMirrors);
        String xml = fetchFromMirrors(encoded);

        Path osmFile = tempDir.resolve("bbox.osm");
        Files.writeString(osmFile, xml, StandardCharsets.UTF_8);
        return osmFile;
    }

    private String buildOverpassXmlQuery(BboxRequest bbox) {
        return """
                [out:xml][timeout:25];
                (
                  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$"](%f,%f,%f,%f);
                );
                out body;
                >;
                out skel qt;\
                """
                .formatted(bbox.south(), bbox.west(), bbox.north(), bbox.east());
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
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
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
    // Parser configuration
    // -------------------------------------------------------------------------

    private WaySegmentParser buildWaySegmentParser(
            BaseGraph graph,
            Directory dir,
            List<ParsedEdge> edgesSink,
            Set<Integer> signalTowersSink) {
        return new WaySegmentParser.Builder(graph.getNodeAccess(), dir)
                .setWayFilter(this::isDrivableHighway)
                // Spike guidance: promote signal-tagged pillar nodes to towers so signal detection
                // always fires at segment endpoints (nodeTags.get(0) or nodeTags.get(last)).
                .setSplitNodeFilter(
                        node -> TRAFFIC_SIGNALS_VALUE.equals(node.getTag(HIGHWAY_TAG)))
                .setEdgeHandler(
                        (from, to, pointList, way, nodeTags) ->
                                handleEdge(
                                        from,
                                        to,
                                        pointList,
                                        way,
                                        nodeTags,
                                        edgesSink,
                                        signalTowersSink))
                .setWorkerThreads(1)
                .build();
    }

    private boolean isDrivableHighway(ReaderWay way) {
        String highway = way.getTag(HIGHWAY_TAG);
        return highway != null && DRIVABLE_HIGHWAYS.contains(highway);
    }

    private void handleEdge(
            int from,
            int to,
            PointList pointList,
            ReaderWay way,
            List<Map<String, Object>> nodeTags,
            List<ParsedEdge> edgesSink,
            Set<Integer> signalTowersSink) {

        // Filter self-loops (Pitfall 4 — RESEARCH.md §9).
        if (from == to) {
            return;
        }

        // Sum-of-segments length — A/B parity with Phase 18 OsmPipelineService lines 565-575.
        double length = 0.0;
        for (int i = 0; i + 1 < pointList.size(); i++) {
            length +=
                    OsmConversionUtils.haversineMeters(
                            pointList.getLat(i),
                            pointList.getLon(i),
                            pointList.getLat(i + 1),
                            pointList.getLon(i + 1));
        }
        if (length < OsmConversionUtils.MIN_ROAD_LENGTH_M) {
            return;
        }

        Map<String, String> tags = copyTags(way);
        // PointList.clone(boolean reverse): false = copy without reversing. Chosen after inspecting
        // graphhopper-10.2 javap output — only method exposing a deep copy of the geometry.
        edgesSink.add(new ParsedEdge(from, to, pointList.clone(false), tags, way.getId()));

        // Node-tag capture — check BOTH endpoints AND intermediate pillars for signal tags. Even
        // with setSplitNodeFilter promoting signal pillars to towers, scanning all positions keeps
        // the detection robust against any residual pillar that carries the tag.
        for (int i = 0; i < nodeTags.size(); i++) {
            Map<String, Object> nt = nodeTags.get(i);
            if (TRAFFIC_SIGNALS_VALUE.equals(nt.get(HIGHWAY_TAG))) {
                // A promoted signal pillar becomes a tower; its tower id is `from` for i==0 or
                // `to` for i==last. Intermediate indices (i > 0 && i < last) should not occur
                // because setSplitNodeFilter forces a split, but we defensively collect from/to
                // if the signal is at an endpoint position.
                if (i == 0) {
                    signalTowersSink.add(from);
                } else if (i == nodeTags.size() - 1) {
                    signalTowersSink.add(to);
                }
            }
        }
    }

    private Map<String, String> copyTags(ReaderWay way) {
        Map<String, String> m = new HashMap<>();
        for (String key : WAY_TAGS_OF_INTEREST) {
            String v = way.getTag(key);
            if (v != null) {
                m.put(key, v);
            }
        }
        return m;
    }

    // -------------------------------------------------------------------------
    // Graph → MapConfig conversion
    // -------------------------------------------------------------------------

    private MapConfig convertToMapConfig(
            List<ParsedEdge> edges,
            Set<Integer> signalTowers,
            NodeAccess na,
            BboxRequest bbox) {

        if (edges.isEmpty()) {
            throw new IllegalStateException("No roads found in selected area");
        }

        TowerClassification cls = classifyTowers(edges, signalTowers);

        // Build RoadConfig list first — we need it for orphan filtering and intersection config.
        List<MapConfig.RoadConfig> roads = buildRoads(edges);

        if (roads.isEmpty()) {
            throw new IllegalStateException("No roads found in selected area");
        }

        // Orphan filtering (RESEARCH.md §9 Pitfall 6): only emit NodeConfig for tower ids that
        // actually appear as from/to on some road.
        Set<String> usedEndpointIds =
                roads.stream()
                        .flatMap(r -> Stream.of(r.getFromNodeId(), r.getToNodeId()))
                        .collect(Collectors.toSet());

        List<MapConfig.NodeConfig> nodes =
                buildNodeConfigs(cls, usedEndpointIds, na, bbox, roads);

        List<MapConfig.IntersectionConfig> intersections =
                buildIntersectionConfigs(cls, signalTowers, usedEndpointIds, roads);

        List<MapConfig.SpawnPointConfig> spawnPoints = new ArrayList<>();
        List<MapConfig.DespawnPointConfig> despawnPoints = new ArrayList<>();
        for (int terminalId : cls.terminalTowers()) {
            String fullId = ID_PREFIX + terminalId;
            if (!usedEndpointIds.contains(fullId)) {
                continue;
            }
            OsmConversionUtils.collectSpawnPoints(fullId, roads, spawnPoints);
            OsmConversionUtils.collectDespawnPoints(fullId, roads, despawnPoints);
        }

        MapConfig cfg =
                OsmConversionUtils.assembleMapConfig(
                        bbox, nodes, roads, intersections, spawnPoints, despawnPoints);

        // Defensive re-validation: per RESEARCH.md §11, if we ever emit validator-rejecting data
        // we fail fast rather than return garbage to the caller.
        List<String> errors = mapValidator.validate(cfg);
        if (!errors.isEmpty()) {
            log.warn("GraphHopper output failed MapValidator: {}", errors);
            throw new IllegalStateException(
                    "GraphHopper output failed validation: " + String.join(", ", errors));
        }

        return cfg;
    }

    private TowerClassification classifyTowers(
            List<ParsedEdge> edges, Set<Integer> signalTowers) {
        Set<Integer> allTowers = new HashSet<>();
        Map<Integer, Integer> degree = new HashMap<>();
        Set<Integer> roundaboutTowers = new HashSet<>();

        for (ParsedEdge pe : edges) {
            allTowers.add(pe.from());
            allTowers.add(pe.to());
            degree.merge(pe.from(), 1, Integer::sum);
            degree.merge(pe.to(), 1, Integer::sum);
            if (ROUNDABOUT_VALUE.equals(pe.tags().get(JUNCTION_TAG))) {
                roundaboutTowers.add(pe.from());
                roundaboutTowers.add(pe.to());
            }
        }

        Set<Integer> intersectionTowers = new HashSet<>();
        Set<Integer> terminalTowers = new HashSet<>();
        for (int id : allTowers) {
            int deg = degree.getOrDefault(id, 0);
            if (signalTowers.contains(id) || roundaboutTowers.contains(id) || deg >= 3) {
                intersectionTowers.add(id);
            } else if (deg == 1) {
                terminalTowers.add(id);
            }
            // deg == 2 (pass-through) with no tag: neither intersection nor terminal. Such a node
            // is a genuine mid-way pillar that for some reason became a tower; it contributes no
            // NodeConfig unless a road references it.
        }

        return new TowerClassification(
                allTowers, degree, roundaboutTowers, intersectionTowers, terminalTowers);
    }

    private List<MapConfig.RoadConfig> buildRoads(List<ParsedEdge> edges) {
        List<MapConfig.RoadConfig> roads = new ArrayList<>();
        for (ParsedEdge pe : edges) {
            Map<String, String> tags = pe.tags();
            String highway = tags.getOrDefault(HIGHWAY_TAG, "residential");
            double speedLimit = OsmConversionUtils.speedLimitForHighway(highway);
            int laneCount = OsmConversionUtils.laneCountForWay(tags, highway);

            // Re-compute length from pillar coords (same idiom as handleEdge, kept here as well
            // because the callback filtered too-short edges already — this path cannot produce a
            // zero-length road).
            double length = computeEdgeLength(pe.geometry());

            boolean isRoundabout = ROUNDABOUT_VALUE.equals(tags.get(JUNCTION_TAG));
            String oneway = tags.get("oneway");
            boolean isOnewayForward =
                    "yes".equals(oneway) || "true".equals(oneway) || isRoundabout;
            boolean isOnewayReverse = "-1".equals(oneway);

            addRoadsForEdge(pe, roads, length, speedLimit, laneCount, isOnewayForward, isOnewayReverse);
        }
        return roads;
    }

    private double computeEdgeLength(PointList pl) {
        double length = 0.0;
        for (int i = 0; i + 1 < pl.size(); i++) {
            length +=
                    OsmConversionUtils.haversineMeters(
                            pl.getLat(i), pl.getLon(i), pl.getLat(i + 1), pl.getLon(i + 1));
        }
        return length;
    }

    private void addRoadsForEdge(
            ParsedEdge pe,
            List<MapConfig.RoadConfig> roads,
            double length,
            double speedLimit,
            int laneCount,
            boolean isOnewayForward,
            boolean isOnewayReverse) {

        String fromFull = ID_PREFIX + pe.from();
        String toFull = ID_PREFIX + pe.to();
        String idBase = ID_PREFIX + pe.osmWayId() + "-" + pe.from() + "-" + pe.to();

        if (isOnewayReverse) {
            roads.add(
                    OsmConversionUtils.buildRoadConfig(
                            idBase + "-fwd",
                            toFull,
                            fromFull,
                            length,
                            speedLimit,
                            laneCount,
                            0.0));
        } else if (isOnewayForward) {
            roads.add(
                    OsmConversionUtils.buildRoadConfig(
                            idBase + "-fwd",
                            fromFull,
                            toFull,
                            length,
                            speedLimit,
                            laneCount,
                            0.0));
        } else {
            double offset = laneCount * OsmConversionUtils.LANE_WIDTH_BACKEND / 2.0 + 1.0;
            roads.add(
                    OsmConversionUtils.buildRoadConfig(
                            idBase + "-fwd",
                            fromFull,
                            toFull,
                            length,
                            speedLimit,
                            laneCount,
                            offset));
            roads.add(
                    OsmConversionUtils.buildRoadConfig(
                            idBase + "-rev",
                            toFull,
                            fromFull,
                            length,
                            speedLimit,
                            laneCount,
                            offset));
        }
    }

    private List<MapConfig.NodeConfig> buildNodeConfigs(
            TowerClassification cls,
            Set<String> usedEndpointIds,
            NodeAccess na,
            BboxRequest bbox,
            List<MapConfig.RoadConfig> roads) {
        List<MapConfig.NodeConfig> nodes = new ArrayList<>();
        for (int towerId : cls.allTowers()) {
            String fullId = ID_PREFIX + towerId;
            if (!usedEndpointIds.contains(fullId)) {
                continue;
            }
            MapConfig.NodeConfig nc = new MapConfig.NodeConfig();
            nc.setId(fullId);
            nc.setType(determineNodeType(fullId, cls, towerId, roads));
            nc.setX(OsmConversionUtils.lonToX(na.getLon(towerId), bbox.west(), bbox.east()));
            nc.setY(OsmConversionUtils.latToY(na.getLat(towerId), bbox.south(), bbox.north()));
            nodes.add(nc);
        }
        return nodes;
    }

    private String determineNodeType(
            String fullId,
            TowerClassification cls,
            int towerId,
            List<MapConfig.RoadConfig> roads) {
        if (cls.intersectionTowers().contains(towerId)) {
            return "INTERSECTION";
        }
        if (cls.terminalTowers().contains(towerId)) {
            // ENTRY if the tower is a fromNodeId of any road; otherwise EXIT.
            boolean isFromNode =
                    roads.stream().anyMatch(r -> r.getFromNodeId().equals(fullId));
            return isFromNode ? "ENTRY" : "EXIT";
        }
        return "ENTRY"; // fallback (pass-through towers should not reach here)
    }

    private List<MapConfig.IntersectionConfig> buildIntersectionConfigs(
            TowerClassification cls,
            Set<Integer> signalTowers,
            Set<String> usedEndpointIds,
            List<MapConfig.RoadConfig> roads) {
        List<MapConfig.IntersectionConfig> intersections = new ArrayList<>();
        for (int towerId : cls.intersectionTowers()) {
            String fullId = ID_PREFIX + towerId;
            if (!usedEndpointIds.contains(fullId)) {
                continue;
            }
            MapConfig.IntersectionConfig ic = new MapConfig.IntersectionConfig();
            ic.setNodeId(fullId);

            if (signalTowers.contains(towerId)) {
                ic.setType("SIGNAL");
                ic.setSignalPhases(OsmConversionUtils.buildDefaultSignalPhases(fullId, roads));
            } else if (cls.roundaboutTowers().contains(towerId)) {
                ic.setType("ROUNDABOUT");
                ic.setRoundaboutCapacity(8);
            } else {
                ic.setType("PRIORITY");
            }
            intersections.add(ic);
        }
        // Refine ENTRY/EXIT for terminal nodes based on actual road direction.
        // (Handled post-hoc by re-walking the node list — not in this method.)
        return intersections;
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("Failed to delete {}: {}", p, e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to clean temp dir {}: {}", dir, e.getMessage());
        }
    }
}
