package com.trafficsimulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MapLoader {

    private static final double LANE_WIDTH_PX = 14.0;

    private final ObjectMapper objectMapper;
    private final MapValidator mapValidator;

    public record LoadedMap(RoadNetwork network, double defaultSpawnRate) {}

    public LoadedMap loadFromClasspath(String resourcePath) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalArgumentException("Map resource not found: " + resourcePath);
        }
        MapConfig config = objectMapper.readValue(is, MapConfig.class);
        log.info("Loaded map config: {} ({})", config.getId(), config.getName());

        // Validate before building — fail fast with structural errors
        List<String> errors = mapValidator.validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Map config validation failed: " + errors);
        }

        RoadNetwork network = buildRoadNetwork(config);
        return new LoadedMap(network, config.getDefaultSpawnRate());
    }

    private RoadNetwork buildRoadNetwork(MapConfig config) {
        // 1. Index nodes by id
        Map<String, MapConfig.NodeConfig> nodes = config.getNodes().stream()
            .collect(Collectors.toMap(MapConfig.NodeConfig::getId, n -> n));

        // 2. Build roads and lanes
        Map<String, Road> roads = new LinkedHashMap<>();
        for (MapConfig.RoadConfig rc : config.getRoads()) {
            MapConfig.NodeConfig fromNode = nodes.get(rc.getFromNodeId());
            MapConfig.NodeConfig toNode   = nodes.get(rc.getToNodeId());

            // Compute pixel coords — offset roads at intersection nodes
            // so inbound/outbound are parallel (not converging to same point)
            double startX = fromNode.getX(), startY = fromNode.getY();
            double endX = toNode.getX(), endY = toNode.getY();

            // If any endpoint is INTERSECTION, offset the ENTIRE road perpendicular
            // so inbound/outbound pairs are parallel (not converging to same point)
            if ("INTERSECTION".equals(fromNode.getType()) || "INTERSECTION".equals(toNode.getType())) {
                double dx = endX - startX;
                double dy = endY - startY;
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len > 0) {
                    // Perpendicular unit vector
                    double px = -dy / len;
                    double py = dx / len;
                    // Offset: all roads shift the same direction relative to their
                    // perpendicular. Since in/out roads have opposite direction vectors,
                    // their perpendiculars are naturally opposite → same offset value
                    // produces parallel separation.
                    double offset = LANE_WIDTH_PX / 2.0;
                    // Shift BOTH endpoints by same offset → road stays parallel to original direction
                    startX += px * offset;
                    startY += py * offset;
                    endX += px * offset;
                    endY += py * offset;
                }
            }

            Road road = Road.builder()
                .id(rc.getId())
                .name(rc.getName())
                .length(rc.getLength())
                .speedLimit(rc.getSpeedLimit())
                .fromNodeId(rc.getFromNodeId())
                .toNodeId(rc.getToNodeId())
                .startX(startX).startY(startY)
                .endX(endX).endY(endY)
                .lanes(new ArrayList<>())
                .build();

            for (int i = 0; i < rc.getLaneCount(); i++) {
                Lane lane = Lane.builder()
                    .id(rc.getId() + "-lane" + i)
                    .laneIndex(i)
                    .road(road)
                    .length(rc.getLength())
                    .maxSpeed(rc.getSpeedLimit())
                    .active(rc.getClosedLanes() == null || !rc.getClosedLanes().contains(i))
                    .build();
                road.getLanes().add(lane);
            }
            roads.put(road.getId(), road);
        }

        // 3. Build spawn/despawn points
        List<SpawnPoint> spawnPoints = config.getSpawnPoints() == null
            ? List.of()
            : config.getSpawnPoints().stream()
                .map(sp -> new SpawnPoint(sp.getRoadId(), sp.getLaneIndex(), sp.getPosition()))
                .toList();

        List<DespawnPoint> despawnPoints = config.getDespawnPoints() == null
            ? List.of()
            : config.getDespawnPoints().stream()
                .map(dp -> new DespawnPoint(dp.getRoadId(), dp.getLaneIndex(), dp.getPosition()))
                .toList();

        // 4. Build intersections
        Map<String, Intersection> intersections = new LinkedHashMap<>();
        if (config.getIntersections() != null) {
            for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
                Intersection ixtn = Intersection.builder()
                    .id(ic.getNodeId())
                    .type(IntersectionType.valueOf(ic.getType()))
                    .intersectionSize(ic.getIntersectionSize())
                    .build();
                intersections.put(ixtn.getId(), ixtn);
            }
        }

        // 4b. Wire intersection road connections
        for (MapConfig.RoadConfig rc : config.getRoads()) {
            // inbound: road's toNodeId matches intersection
            Intersection toIxtn = intersections.get(rc.getToNodeId());
            if (toIxtn != null) {
                toIxtn.getInboundRoadIds().add(rc.getId());
                toIxtn.getConnectedRoadIds().add(rc.getId());
            }
            // outbound: road's fromNodeId matches intersection
            Intersection fromIxtn = intersections.get(rc.getFromNodeId());
            if (fromIxtn != null) {
                fromIxtn.getOutboundRoadIds().add(rc.getId());
                fromIxtn.getConnectedRoadIds().add(rc.getId());
            }
        }

        // 4c. Build TrafficLight for SIGNAL intersections
        if (config.getIntersections() != null) {
            for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
                if ("SIGNAL".equals(ic.getType()) && ic.getSignalPhases() != null) {
                    Intersection ixtn = intersections.get(ic.getNodeId());
                    List<TrafficLightPhase> phases = ic.getSignalPhases().stream()
                        .map(sp -> TrafficLightPhase.builder()
                            .greenRoadIds(new HashSet<>(sp.getGreenRoadIds()))
                            .durationMs(sp.getDurationMs())
                            .type(sp.getType() == null ? TrafficLightPhase.PhaseType.GREEN
                                : TrafficLightPhase.PhaseType.valueOf(sp.getType()))
                            .build())
                        .toList();
                    TrafficLight light = TrafficLight.builder()
                        .intersectionId(ic.getNodeId())
                        .phases(new ArrayList<>(phases))
                        .currentPhaseIndex(0)
                        .phaseElapsedMs(0)
                        .build();
                    ixtn.setTrafficLight(light);
                }
            }
        }

        RoadNetwork network = RoadNetwork.builder()
            .id(config.getId())
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(spawnPoints)
            .despawnPoints(despawnPoints)
            .build();

        log.info("Built road network '{}': {} roads, {} lanes total, {} spawn points",
            network.getId(), roads.size(),
            roads.values().stream().mapToInt(r -> r.getLanes().size()).sum(),
            spawnPoints.size());

        return network;
    }
}
