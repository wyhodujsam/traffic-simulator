package com.trafficsimulator.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class MapValidator {

    private static final String ROAD_PREFIX = "Road ";
    private static final String INTERSECTION_PREFIX = "Intersection ";

    public List<String> validate(MapConfig config) {
        List<String> errors = new ArrayList<>();
        validateBasicFields(config, errors);
        Set<String> nodeIds = validateNodes(config, errors);
        Set<String> roadIds = validateRoads(config, errors, nodeIds);
        validateSpawnAndDespawnPoints(config, errors, roadIds);
        validateIntersections(config, errors, roadIds);
        logResult(config, errors);
        return errors;
    }

    private void validateBasicFields(MapConfig config, List<String> errors) {
        if (config.getId() == null || config.getId().isBlank()) {
            errors.add("Map id is required");
        }
        if (config.getNodes() == null || config.getNodes().isEmpty()) {
            errors.add("At least one node is required");
        }
        if (config.getRoads() == null || config.getRoads().isEmpty()) {
            errors.add("At least one road is required");
        }
    }

    private Set<String> validateNodes(MapConfig config, List<String> errors) {
        if (config.getNodes() == null) {
            return new HashSet<>();
        }
        Set<String> nodeIds =
                config.getNodes().stream()
                        .map(MapConfig.NodeConfig::getId)
                        .collect(Collectors.toSet());
        if (nodeIds.size() != config.getNodes().size()) {
            errors.add("Duplicate node IDs detected");
        }
        return nodeIds;
    }

    private Set<String> validateRoads(MapConfig config, List<String> errors, Set<String> nodeIds) {
        if (config.getRoads() == null) {
            return new HashSet<>();
        }
        Set<String> roadIds = new HashSet<>();
        for (MapConfig.RoadConfig road : config.getRoads()) {
            roadIds.add(road.getId());
            validateRoadNodeRefs(road, errors, nodeIds);
            validateRoadConstraints(road, errors);
        }
        return roadIds;
    }

    private void validateRoadNodeRefs(
            MapConfig.RoadConfig road, List<String> errors, Set<String> nodeIds) {
        if (!nodeIds.contains(road.getFromNodeId())) {
            errors.add(
                    ROAD_PREFIX
                            + road.getId()
                            + " references unknown fromNodeId: "
                            + road.getFromNodeId());
        }
        if (!nodeIds.contains(road.getToNodeId())) {
            errors.add(
                    ROAD_PREFIX
                            + road.getId()
                            + " references unknown toNodeId: "
                            + road.getToNodeId());
        }
    }

    private void validateRoadConstraints(MapConfig.RoadConfig road, List<String> errors) {
        if (road.getLaneCount() < 1 || road.getLaneCount() > 4) {
            errors.add(
                    ROAD_PREFIX
                            + road.getId()
                            + " laneCount must be 1-4, got: "
                            + road.getLaneCount());
        }
        if (road.getLength() <= 0) {
            errors.add(ROAD_PREFIX + road.getId() + " length must be positive");
        }
        if (road.getSpeedLimit() <= 0) {
            errors.add(ROAD_PREFIX + road.getId() + " speedLimit must be positive");
        }
    }

    private void validateSpawnAndDespawnPoints(
            MapConfig config, List<String> errors, Set<String> roadIds) {
        if (roadIds.isEmpty()) {
            return;
        }
        if (config.getSpawnPoints() != null) {
            for (MapConfig.SpawnPointConfig sp : config.getSpawnPoints()) {
                if (!roadIds.contains(sp.getRoadId())) {
                    errors.add("SpawnPoint references unknown roadId: " + sp.getRoadId());
                }
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

    private void validateIntersections(MapConfig config, List<String> errors, Set<String> roadIds) {
        if (config.getIntersections() == null || config.getRoads() == null) {
            return;
        }

        Set<String> connectedNodeIds = buildConnectedNodeIds(config);

        for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
            if (!connectedNodeIds.contains(ic.getNodeId())) {
                errors.add(INTERSECTION_PREFIX + ic.getNodeId() + " has no roads connecting to it");
            }
            if ("SIGNAL".equals(ic.getType())) {
                validateSignalPhases(ic, errors, roadIds);
            }
        }
    }

    private Set<String> buildConnectedNodeIds(MapConfig config) {
        Set<String> connectedNodeIds = new HashSet<>();
        for (MapConfig.RoadConfig road : config.getRoads()) {
            connectedNodeIds.add(road.getFromNodeId());
            connectedNodeIds.add(road.getToNodeId());
        }
        return connectedNodeIds;
    }

    private void validateSignalPhases(
            MapConfig.IntersectionConfig ic, List<String> errors, Set<String> roadIds) {
        if (ic.getSignalPhases() == null || ic.getSignalPhases().isEmpty()) {
            errors.add(
                    "SIGNAL intersection " + ic.getNodeId() + " must have non-empty signalPhases");
            return;
        }
        for (int i = 0; i < ic.getSignalPhases().size(); i++) {
            MapConfig.SignalPhaseConfig sp = ic.getSignalPhases().get(i);
            if (sp.getDurationMs() <= 0) {
                errors.add(
                        INTERSECTION_PREFIX
                                + ic.getNodeId()
                                + " phase "
                                + i
                                + " durationMs must be > 0");
            }
            validateSignalPhaseRoadRefs(ic, sp, i, errors, roadIds);
        }
    }

    private void validateSignalPhaseRoadRefs(
            MapConfig.IntersectionConfig ic,
            MapConfig.SignalPhaseConfig sp,
            int phaseIndex,
            List<String> errors,
            Set<String> roadIds) {
        if (sp.getGreenRoadIds() == null) {
            return;
        }
        for (String greenRoadId : sp.getGreenRoadIds()) {
            if (!roadIds.contains(greenRoadId)) {
                errors.add(
                        INTERSECTION_PREFIX
                                + ic.getNodeId()
                                + " phase "
                                + phaseIndex
                                + " references unknown road: "
                                + greenRoadId);
            }
        }
    }

    private void logResult(MapConfig config, List<String> errors) {
        if (errors.isEmpty()) {
            log.info("Map config '{}' validation passed", config.getId());
        } else {
            log.warn("Map config validation failed with {} errors", errors.size());
        }
    }
}
