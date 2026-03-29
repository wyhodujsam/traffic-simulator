package com.trafficsimulator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class MapValidator {

    public List<String> validate(MapConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.getId() == null || config.getId().isBlank()) {
            errors.add("Map id is required");
        }
        if (config.getNodes() == null || config.getNodes().isEmpty()) {
            errors.add("At least one node is required");
        }
        if (config.getRoads() == null || config.getRoads().isEmpty()) {
            errors.add("At least one road is required");
        }

        // Validate node IDs are unique
        if (config.getNodes() != null) {
            Set<String> nodeIds = config.getNodes().stream()
                .map(MapConfig.NodeConfig::getId)
                .collect(Collectors.toSet());
            if (nodeIds.size() != config.getNodes().size()) {
                errors.add("Duplicate node IDs detected");
            }

            // Validate road node references
            if (config.getRoads() != null) {
                for (MapConfig.RoadConfig road : config.getRoads()) {
                    if (!nodeIds.contains(road.getFromNodeId())) {
                        errors.add("Road " + road.getId() + " references unknown fromNodeId: " + road.getFromNodeId());
                    }
                    if (!nodeIds.contains(road.getToNodeId())) {
                        errors.add("Road " + road.getId() + " references unknown toNodeId: " + road.getToNodeId());
                    }
                    if (road.getLaneCount() < 1 || road.getLaneCount() > 4) {
                        errors.add("Road " + road.getId() + " laneCount must be 1-4, got: " + road.getLaneCount());
                    }
                    if (road.getLength() <= 0) {
                        errors.add("Road " + road.getId() + " length must be positive");
                    }
                    if (road.getSpeedLimit() <= 0) {
                        errors.add("Road " + road.getId() + " speedLimit must be positive");
                    }
                }
            }
        }

        // Validate spawn points reference existing roads
        if (config.getSpawnPoints() != null && config.getRoads() != null) {
            Set<String> roadIds = config.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());
            for (MapConfig.SpawnPointConfig sp : config.getSpawnPoints()) {
                if (!roadIds.contains(sp.getRoadId())) {
                    errors.add("SpawnPoint references unknown roadId: " + sp.getRoadId());
                }
            }
            if (config.getDespawnPoints() != null) {
                for (MapConfig.DespawnPointConfig dp : config.getDespawnPoints()) {
                    if (!roadIds.contains(dp.getRoadId())) {
                        errors.add("DespawnPoint references unknown roadId: " + dp.getRoadId());
                    }
                }
            }
        }

        // Validate intersection signal phases
        if (config.getIntersections() != null && config.getRoads() != null) {
            Set<String> roadIds = config.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());

            // Collect all node IDs that have at least one road connecting
            Set<String> connectedNodeIds = new java.util.HashSet<>();
            for (MapConfig.RoadConfig road : config.getRoads()) {
                connectedNodeIds.add(road.getFromNodeId());
                connectedNodeIds.add(road.getToNodeId());
            }

            for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
                // Validate orphan intersection nodes
                if (!connectedNodeIds.contains(ic.getNodeId())) {
                    errors.add("Intersection " + ic.getNodeId() + " has no roads connecting to it");
                }

                if ("SIGNAL".equals(ic.getType())) {
                    if (ic.getSignalPhases() == null || ic.getSignalPhases().isEmpty()) {
                        errors.add("SIGNAL intersection " + ic.getNodeId() + " must have non-empty signalPhases");
                    } else {
                        for (int i = 0; i < ic.getSignalPhases().size(); i++) {
                            MapConfig.SignalPhaseConfig sp = ic.getSignalPhases().get(i);
                            if (sp.getDurationMs() <= 0) {
                                errors.add("Intersection " + ic.getNodeId() + " phase " + i + " durationMs must be > 0");
                            }
                            if (sp.getGreenRoadIds() != null) {
                                for (String greenRoadId : sp.getGreenRoadIds()) {
                                    if (!roadIds.contains(greenRoadId)) {
                                        errors.add("Intersection " + ic.getNodeId() + " phase " + i
                                            + " references unknown road: " + greenRoadId);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (errors.isEmpty()) {
            log.info("Map config '{}' validation passed", config.getId());
        } else {
            log.warn("Map config validation failed with {} errors", errors.size());
        }

        return errors;
    }
}
