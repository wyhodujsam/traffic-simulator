package com.trafficsimulator.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.model.DespawnPoint;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.SpawnPoint;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.TrafficLightPhase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class MapLoader {

    private static final double LANE_WIDTH_PX = 14.0;

    private final ObjectMapper objectMapper;
    private final MapValidator mapValidator;

    public record LoadedMap(RoadNetwork network, double defaultSpawnRate) {}

    public LoadedMap loadFromConfig(MapConfig config) {
        List<String> errors = mapValidator.validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Map config validation failed: " + errors);
        }
        RoadNetwork network = buildRoadNetwork(config);
        return new LoadedMap(network, config.getDefaultSpawnRate());
    }

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
        Map<String, MapConfig.NodeConfig> nodes =
                config.getNodes().stream()
                        .collect(Collectors.toMap(MapConfig.NodeConfig::getId, n -> n));

        Map<String, Road> roads = buildRoads(config, nodes);
        List<SpawnPoint> spawnPoints = buildSpawnPoints(config);
        List<DespawnPoint> despawnPoints = buildDespawnPoints(config);
        Map<String, Intersection> intersections = buildIntersections(config, nodes);
        wireIntersectionConnections(config, intersections);
        buildTrafficLights(config, intersections);

        RoadNetwork network =
                RoadNetwork.builder()
                        .id(config.getId())
                        .roads(roads)
                        .intersections(intersections)
                        .spawnPoints(spawnPoints)
                        .despawnPoints(despawnPoints)
                        .build();

        log.info(
                "Built road network '{}': {} roads, {} lanes total, {} spawn points",
                network.getId(),
                roads.size(),
                roads.values().stream().mapToInt(r -> r.getLanes().size()).sum(),
                spawnPoints.size());

        return network;
    }

    private Map<String, Road> buildRoads(
            MapConfig config, Map<String, MapConfig.NodeConfig> nodes) {
        Map<String, Road> roads = new LinkedHashMap<>();
        for (MapConfig.RoadConfig rc : config.getRoads()) {
            Road road = buildSingleRoad(rc, nodes);
            roads.put(road.getId(), road);
        }
        return roads;
    }

    private Road buildSingleRoad(MapConfig.RoadConfig rc, Map<String, MapConfig.NodeConfig> nodes) {
        MapConfig.NodeConfig fromNode = nodes.get(rc.getFromNodeId());
        MapConfig.NodeConfig toNode = nodes.get(rc.getToNodeId());

        double[] coords = computeRoadCoords(fromNode, toNode);

        Road road =
                Road.builder()
                        .id(rc.getId())
                        .name(rc.getName())
                        .length(rc.getLength())
                        .speedLimit(rc.getSpeedLimit())
                        .fromNodeId(rc.getFromNodeId())
                        .toNodeId(rc.getToNodeId())
                        .startX(coords[0])
                        .startY(coords[1])
                        .endX(coords[2])
                        .endY(coords[3])
                        .lanes(new ArrayList<>())
                        .build();

        for (int i = 0; i < rc.getLaneCount(); i++) {
            Lane lane =
                    Lane.builder()
                            .id(rc.getId() + "-lane" + i)
                            .laneIndex(i)
                            .road(road)
                            .length(rc.getLength())
                            .maxSpeed(rc.getSpeedLimit())
                            .active(rc.getClosedLanes() == null || !rc.getClosedLanes().contains(i))
                            .build();
            road.getLanes().add(lane);
        }
        return road;
    }

    private double[] computeRoadCoords(MapConfig.NodeConfig fromNode, MapConfig.NodeConfig toNode) {
        double startX = fromNode.getX();
        double startY = fromNode.getY();
        double endX = toNode.getX();
        double endY = toNode.getY();

        if (!"INTERSECTION".equals(fromNode.getType())
                && !"INTERSECTION".equals(toNode.getType())) {
            return new double[] {startX, startY, endX, endY};
        }

        double dx = endX - startX;
        double dy = endY - startY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len <= 0) {
            return new double[] {startX, startY, endX, endY};
        }

        double px = -dy / len;
        double py = dx / len;
        double offset = LANE_WIDTH_PX / 2.0;
        return new double[] {
            startX + px * offset, startY + py * offset,
            endX + px * offset, endY + py * offset
        };
    }

    private List<SpawnPoint> buildSpawnPoints(MapConfig config) {
        if (config.getSpawnPoints() == null) {
            return List.of();
        }
        return config.getSpawnPoints().stream()
                .map(sp -> new SpawnPoint(sp.getRoadId(), sp.getLaneIndex(), sp.getPosition()))
                .toList();
    }

    private List<DespawnPoint> buildDespawnPoints(MapConfig config) {
        if (config.getDespawnPoints() == null) {
            return List.of();
        }
        return config.getDespawnPoints().stream()
                .map(dp -> new DespawnPoint(dp.getRoadId(), dp.getLaneIndex(), dp.getPosition()))
                .toList();
    }

    private Map<String, Intersection> buildIntersections(
            MapConfig config, Map<String, MapConfig.NodeConfig> nodes) {
        Map<String, Intersection> intersections = new LinkedHashMap<>();
        if (config.getIntersections() == null) {
            return intersections;
        }

        for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
            MapConfig.NodeConfig node = nodes.get(ic.getNodeId());
            Intersection ixtn =
                    Intersection.builder()
                            .id(ic.getNodeId())
                            .type(IntersectionType.valueOf(ic.getType()))
                            .intersectionSize(ic.getIntersectionSize())
                            .roundaboutCapacity(ic.getRoundaboutCapacity())
                            .centerX(node != null ? node.getX() : 0)
                            .centerY(node != null ? node.getY() : 0)
                            .build();
            intersections.put(ixtn.getId(), ixtn);
        }
        return intersections;
    }

    private void wireIntersectionConnections(
            MapConfig config, Map<String, Intersection> intersections) {
        for (MapConfig.RoadConfig rc : config.getRoads()) {
            Intersection toIxtn = intersections.get(rc.getToNodeId());
            if (toIxtn != null) {
                toIxtn.getInboundRoadIds().add(rc.getId());
                toIxtn.getConnectedRoadIds().add(rc.getId());
            }
            Intersection fromIxtn = intersections.get(rc.getFromNodeId());
            if (fromIxtn != null) {
                fromIxtn.getOutboundRoadIds().add(rc.getId());
                fromIxtn.getConnectedRoadIds().add(rc.getId());
            }
        }
    }

    private void buildTrafficLights(MapConfig config, Map<String, Intersection> intersections) {
        if (config.getIntersections() == null) {
            return;
        }

        for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
            if (!"SIGNAL".equals(ic.getType()) || ic.getSignalPhases() == null) {
                continue;
            }

            Intersection ixtn = intersections.get(ic.getNodeId());
            List<TrafficLightPhase> phases =
                    ic.getSignalPhases().stream()
                            .map(
                                    sp ->
                                            TrafficLightPhase.builder()
                                                    .greenRoadIds(
                                                            new HashSet<>(sp.getGreenRoadIds()))
                                                    .durationMs(sp.getDurationMs())
                                                    .type(
                                                            sp.getType() == null
                                                                    ? TrafficLightPhase.PhaseType
                                                                            .GREEN
                                                                    : TrafficLightPhase.PhaseType
                                                                            .valueOf(sp.getType()))
                                                    .build())
                            .toList();
            TrafficLight light =
                    TrafficLight.builder()
                            .intersectionId(ic.getNodeId())
                            .phases(new ArrayList<>(phases))
                            .currentPhaseIndex(0)
                            .phaseElapsedMs(0)
                            .build();
            ixtn.setTrafficLight(light);
        }
    }
}
