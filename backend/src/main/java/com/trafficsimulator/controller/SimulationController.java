package com.trafficsimulator.controller;

import com.trafficsimulator.dto.LaneDto;
import com.trafficsimulator.dto.RoadDto;
import com.trafficsimulator.dto.SimulationStatusDto;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            List<LaneDto> laneDtos = road.getLanes().stream()
                .map(lane -> LaneDto.builder()
                    .id(lane.getId())
                    .laneIndex(lane.getLaneIndex())
                    .active(lane.isActive())
                    .build())
                .toList();
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
                .lanes(laneDtos)
                .build());
        }
        return result;
    }

    /**
     * Dumps full road state for debugging: every vehicle and obstacle on every lane
     * with position, speed, acceleration, and flags.
     */
    @GetMapping("/debug/dump")
    public Map<String, Object> dumpState() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("tick", simulationEngine.getTickCounter().get());
        dump.put("status", simulationEngine.getStatus().name());

        RoadNetwork network = simulationEngine.getRoadNetwork();
        if (network == null) {
            dump.put("network", null);
            return dump;
        }

        List<Map<String, Object>> roadsData = new ArrayList<>();
        for (Road road : network.getRoads().values()) {
            Map<String, Object> roadData = new LinkedHashMap<>();
            roadData.put("id", road.getId());
            roadData.put("length", road.getLength());

            List<Map<String, Object>> lanesData = new ArrayList<>();
            for (Lane lane : road.getLanes()) {
                Map<String, Object> laneData = new LinkedHashMap<>();
                laneData.put("id", lane.getId());
                laneData.put("index", lane.getLaneIndex());
                laneData.put("active", lane.isActive());
                laneData.put("vehicleCount", lane.getVehicles().size());
                laneData.put("obstacleCount", lane.getObstacles().size());

                List<Map<String, Object>> vehicles = new ArrayList<>();
                for (Vehicle v : lane.getVehicles()) {
                    Map<String, Object> vd = new LinkedHashMap<>();
                    vd.put("id", v.getId());
                    vd.put("pos", Math.round(v.getPosition() * 10.0) / 10.0);
                    vd.put("speed", Math.round(v.getSpeed() * 100.0) / 100.0);
                    vd.put("accel", Math.round(v.getAcceleration() * 100.0) / 100.0);
                    vd.put("forceLaneChange", v.isForceLaneChange());
                    vd.put("zipperCandidate", v.isZipperCandidate());
                    vd.put("laneChangeProgress", v.getLaneChangeProgress());
                    vehicles.add(vd);
                }
                laneData.put("vehicles", vehicles);

                List<Map<String, Object>> obstacles = new ArrayList<>();
                for (Obstacle obs : lane.getObstacles()) {
                    Map<String, Object> od = new LinkedHashMap<>();
                    od.put("id", obs.getId());
                    od.put("pos", obs.getPosition());
                    od.put("length", obs.getLength());
                    obstacles.add(od);
                }
                laneData.put("obstacles", obstacles);

                lanesData.add(laneData);
            }
            roadData.put("lanes", lanesData);
            roadsData.add(roadData);
        }
        dump.put("roads", roadsData);
        return dump;
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
