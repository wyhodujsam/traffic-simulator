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

    private final ObjectMapper objectMapper;
    private final MapValidator mapValidator;

    public RoadNetwork loadFromClasspath(String resourcePath) throws IOException {
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

        return buildRoadNetwork(config);
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

            Road road = Road.builder()
                .id(rc.getId())
                .name(rc.getName())
                .length(rc.getLength())
                .speedLimit(rc.getSpeedLimit())
                .fromNodeId(rc.getFromNodeId())
                .toNodeId(rc.getToNodeId())
                .startX(fromNode.getX()).startY(fromNode.getY())
                .endX(toNode.getX()).endY(toNode.getY())
                .lanes(new ArrayList<>())
                .build();

            for (int i = 0; i < rc.getLaneCount(); i++) {
                Lane lane = Lane.builder()
                    .id(rc.getId() + "-lane" + i)
                    .laneIndex(i)
                    .road(road)
                    .length(rc.getLength())
                    .maxSpeed(rc.getSpeedLimit())
                    .active(true)
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

        // 4. Build intersections (empty in Phase 2)
        Map<String, Intersection> intersections = new LinkedHashMap<>();
        if (config.getIntersections() != null) {
            for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
                Intersection ixtn = Intersection.builder()
                    .id(ic.getNodeId())
                    .type(IntersectionType.valueOf(ic.getType()))
                    .build();
                intersections.put(ixtn.getId(), ixtn);
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
