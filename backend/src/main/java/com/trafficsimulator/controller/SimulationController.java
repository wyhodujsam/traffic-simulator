package com.trafficsimulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.LaneDto;
import com.trafficsimulator.dto.IntersectionDto;
import com.trafficsimulator.dto.MapInfoDto;
import com.trafficsimulator.dto.RoadDto;
import com.trafficsimulator.dto.SimulationStatusDto;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationEngine simulationEngine;
    private final ObjectMapper objectMapper;

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
                    vehicleCount += lane.getVehicleCount();
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
        Map<String, Intersection> intersections = network.getIntersections();
        for (Road road : network.getRoads().values()) {
            List<LaneDto> laneDtos = road.getLanes().stream()
                .map(lane -> LaneDto.builder()
                    .id(lane.getId())
                    .laneIndex(lane.getLaneIndex())
                    .active(lane.isActive())
                    .build())
                .toList();

            // Compute clip distances based on intersection sizes
            double clipStart = 0;
            double clipEnd = 0;
            Intersection fromIxtn = intersections.get(road.getFromNodeId());
            Intersection toIxtn = intersections.get(road.getToNodeId());
            if (fromIxtn != null && fromIxtn.getIntersectionSize() > 0) {
                clipStart = fromIxtn.getIntersectionSize() / 2.0;
            }
            if (toIxtn != null && toIxtn.getIntersectionSize() > 0) {
                clipEnd = toIxtn.getIntersectionSize() / 2.0;
            }

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
                .clipStart(clipStart)
                .clipEnd(clipEnd)
                .build());
        }
        return result;
    }

    /**
     * Returns intersection geometry for the currently loaded map.
     * Used by frontend to render intersection boxes on canvas.
     */
    @GetMapping("/intersections")
    public List<IntersectionDto> getIntersections() {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        if (network == null) return List.of();

        List<IntersectionDto> result = new ArrayList<>();
        for (Intersection ixtn : network.getIntersections().values()) {
            double sumX = 0, sumY = 0;
            int count = 0;
            int maxLaneCount = 1;
            for (String roadId : ixtn.getConnectedRoadIds()) {
                Road road = network.getRoads().get(roadId);
                if (road == null) continue;
                if (road.getToNodeId().equals(ixtn.getId())) {
                    sumX += road.getEndX(); sumY += road.getEndY(); count++;
                } else if (road.getFromNodeId().equals(ixtn.getId())) {
                    sumX += road.getStartX(); sumY += road.getStartY(); count++;
                }
                maxLaneCount = Math.max(maxLaneCount, road.getLanes().size());
            }
            double cx, cy;
            if (count == 0) {
                cx = ixtn.getCenterX();
                cy = ixtn.getCenterY();
            } else {
                cx = sumX / count;
                cy = sumY / count;
            }

            // Use explicit intersectionSize from config if set, otherwise derive from widest road
            double size = ixtn.getIntersectionSize() > 0
                ? ixtn.getIntersectionSize()
                : maxLaneCount * 14.0;
            result.add(IntersectionDto.builder()
                .id(ixtn.getId())
                .type(ixtn.getType().name())
                .x(cx).y(cy)
                .size(size)
                .build());
        }
        return result;
    }

    @GetMapping("/debug/traffic-lights")
    public List<Map<String, Object>> debugTrafficLights() {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        if (network == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Intersection ixtn : network.getIntersections().values()) {
            if (ixtn.getTrafficLight() == null) continue;
            var tl = ixtn.getTrafficLight();
            var phase = tl.getCurrentPhase();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("intersectionId", ixtn.getId());
            info.put("phaseIndex", tl.getCurrentPhaseIndex());
            info.put("phaseType", phase.getType().name());
            info.put("greenRoads", phase.getGreenRoadIds());
            info.put("elapsedMs", tl.getPhaseElapsedMs());
            info.put("durationMs", phase.getDurationMs());
            for (String inRoadId : ixtn.getInboundRoadIds()) {
                info.put("signal_" + inRoadId, tl.getSignalState(inRoadId));
            }
            result.add(info);
        }
        return result;
    }

    /**
     * REST command endpoint for scripted testing (mirrors STOMP CommandHandler).
     */
    @PostMapping("/command")
    public Map<String, String> postCommand(@RequestBody com.trafficsimulator.dto.CommandDto dto) {
        com.trafficsimulator.engine.command.SimulationCommand command = switch (dto.getType()) {
            case "START"                -> new com.trafficsimulator.engine.command.SimulationCommand.Start();
            case "STOP"                 -> new com.trafficsimulator.engine.command.SimulationCommand.Stop();
            case "PAUSE"               -> new com.trafficsimulator.engine.command.SimulationCommand.Pause();
            case "RESUME"              -> new com.trafficsimulator.engine.command.SimulationCommand.Resume();
            case "SET_SPAWN_RATE"      -> new com.trafficsimulator.engine.command.SimulationCommand.SetSpawnRate(dto.getSpawnRate());
            case "SET_SPEED_MULTIPLIER"-> new com.trafficsimulator.engine.command.SimulationCommand.SetSpeedMultiplier(dto.getMultiplier());
            case "SET_MAX_SPEED"       -> new com.trafficsimulator.engine.command.SimulationCommand.SetMaxSpeed(dto.getMaxSpeed());
            case "ADD_OBSTACLE"        -> new com.trafficsimulator.engine.command.SimulationCommand.AddObstacle(dto.getRoadId(), dto.getLaneIndex(), dto.getPosition());
            case "REMOVE_OBSTACLE"     -> new com.trafficsimulator.engine.command.SimulationCommand.RemoveObstacle(dto.getObstacleId());
            case "CLOSE_LANE"          -> new com.trafficsimulator.engine.command.SimulationCommand.CloseLane(dto.getRoadId(), dto.getLaneIndex());
            case "LOAD_MAP"            -> new com.trafficsimulator.engine.command.SimulationCommand.LoadMap(dto.getMapId());
            default -> throw new IllegalArgumentException("Unknown: " + dto.getType());
        };
        simulationEngine.enqueue(command);
        return Map.of("status", "ok");
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
                laneData.put("vehicleCount", lane.getVehicleCount());
                laneData.put("obstacleCount", lane.getObstaclesView().size());

                List<Map<String, Object>> vehicles = new ArrayList<>();
                for (Vehicle v : lane.getVehiclesView()) {
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
                for (Obstacle obs : lane.getObstaclesView()) {
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
     * Returns list of available maps with metadata (id, name, description).
     * Scans classpath:maps/ directory for JSON files.
     */
    @GetMapping("/maps")
    public List<MapInfoDto> listMaps() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:maps/*.json");
        List<MapInfoDto> maps = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                MapConfig config = objectMapper.readValue(is, MapConfig.class);
                maps.add(MapInfoDto.builder()
                    .id(config.getId())
                    .name(config.getName())
                    .description(config.getDescription())
                    .build());
            }
        }
        return maps;
    }
}
