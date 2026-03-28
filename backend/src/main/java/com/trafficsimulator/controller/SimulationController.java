package com.trafficsimulator.controller;

import com.trafficsimulator.dto.RoadDto;
import com.trafficsimulator.dto.SimulationStatusDto;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationEngine simulationEngine;

    /**
     * Returns current simulation status for frontend initialization / debugging.
     */
    @GetMapping("/simulation/status")
    public SimulationStatusDto getStatus() {
        RoadNetwork network = simulationEngine.getRoadNetwork();

        int vehicleCount = 0;
        if (network != null) {
            for (Road road : network.getRoads().values()) {
                for (Lane lane : road.getLanes()) {
                    vehicleCount += lane.getVehicles().size();
                }
            }
        }

        return SimulationStatusDto.builder()
            .status(simulationEngine.getStatus().name())
            .tick(simulationEngine.getTickCounter().get())
            .vehicleCount(vehicleCount)
            .speedMultiplier(simulationEngine.getSpeedMultiplier())
            .spawnRate(simulationEngine.getSpawnRate())
            .mapId(network != null ? network.getId() : null)
            .maxSpeed(simulationEngine.getMaxSpeed())
            .build();
    }

    /**
     * Returns road geometry for the currently loaded map.
     * Used by frontend to render road segments on canvas.
     */
    @GetMapping("/roads")
    public List<RoadDto> getRoads() {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        if (network == null) {
            return List.of();
        }
        List<RoadDto> result = new ArrayList<>();
        for (Road road : network.getRoads().values()) {
            result.add(RoadDto.builder()
                .id(road.getId())
                .name(road.getName())
                .laneCount(road.getLanes().size())
                .length(road.getLength())
                .speedLimit(road.getSpeedLimit())
                .startX(road.getStartX())
                .startY(road.getStartY())
                .endX(road.getEndX())
                .endY(road.getEndY())
                .build());
        }
        return result;
    }

    /**
     * Returns list of available map file names (without path prefix and .json suffix).
     * Scans classpath:maps/ directory for JSON files.
     */
    @GetMapping("/maps")
    public List<String> listMaps() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:maps/*.json");
        List<String> mapIds = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename != null && filename.endsWith(".json")) {
                mapIds.add(filename.replace(".json", ""));
            }
        }
        return mapIds;
    }
}
