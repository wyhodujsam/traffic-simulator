package com.trafficsimulator.scheduler;

import com.trafficsimulator.dto.ObstacleDto;
import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.dto.StatsDto;
import com.trafficsimulator.dto.TrafficLightDto;
import com.trafficsimulator.dto.VehicleDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.Vehicle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SnapshotBuilder {

    /**
     * Builds a complete SimulationStateDto snapshot from the current state.
     */
    public SimulationStateDto buildSnapshot(RoadNetwork network, long tick,
            String status, double spawnRate, double speedMultiplier,
            IVehicleSpawner vehicleSpawner, String mapId, String error) {

        List<VehicleDto> vehicleDtos = new ArrayList<>();
        List<ObstacleDto> obstacleDtos = new ArrayList<>();
        double totalSpeed = 0.0;
        int vehicleCount = 0;
        double totalRoadLength = 0.0;

        if (network != null) {
            for (Road road : network.getRoads().values()) {
                totalRoadLength += road.getLength();
                for (int laneIdx = 0; laneIdx < road.getLanes().size(); laneIdx++) {
                    Lane lane = road.getLanes().get(laneIdx);
                    for (Vehicle v : lane.getVehiclesView()) {
                        vehicleDtos.add(buildVehicleDto(v, road, laneIdx));
                        totalSpeed += v.getSpeed();
                        vehicleCount++;
                    }
                    for (Obstacle obs : lane.getObstaclesView()) {
                        obstacleDtos.add(buildObstacleDto(obs, road, laneIdx));
                    }
                }
            }
        }

        List<TrafficLightDto> trafficLightDtos = new ArrayList<>();
        if (network != null) {
            for (Intersection ixtn : network.getIntersections().values()) {
                if (ixtn.getTrafficLight() == null) continue;
                TrafficLight tl = ixtn.getTrafficLight();
                for (String inRoadId : ixtn.getInboundRoadIds()) {
                    Road inRoad = network.getRoads().get(inRoadId);
                    if (inRoad == null) continue;
                    trafficLightDtos.add(TrafficLightDto.builder()
                        .intersectionId(ixtn.getId())
                        .roadId(inRoadId)
                        .state(tl.getSignalState(inRoadId))
                        .x(inRoad.getEndX())
                        .y(inRoad.getEndY())
                        .angle(Math.atan2(inRoad.getEndY() - inRoad.getStartY(),
                                           inRoad.getEndX() - inRoad.getStartX()))
                        .build());
                }
            }
        }

        double avgSpeed = vehicleCount > 0 ? totalSpeed / vehicleCount : 0.0;
        double density = totalRoadLength > 0
            ? (vehicleCount / (totalRoadLength / 1000.0))
            : 0.0;

        StatsDto stats = StatsDto.builder()
            .vehicleCount(vehicleCount)
            .avgSpeed(avgSpeed)
            .density(density)
            .throughput(vehicleSpawner.getThroughput())
            .build();

        return SimulationStateDto.builder()
            .tick(tick)
            .timestamp(System.currentTimeMillis())
            .status(status)
            .vehicles(vehicleDtos)
            .obstacles(obstacleDtos)
            .trafficLights(trafficLightDtos)
            .stats(stats)
            .mapId(mapId)
            .error(error)
            .build();
    }

    /**
     * Maps a domain Vehicle to a VehicleDto with domain coordinates.
     */
    VehicleDto buildVehicleDto(Vehicle v, Road road, int laneIndex) {
        return VehicleDto.builder()
            .id(v.getId())
            .roadId(road.getId())
            .laneId(v.getLane().getId())
            .laneIndex(laneIndex)
            .position(v.getPosition())
            .speed(v.getSpeed())
            .laneChangeProgress(v.getLaneChangeProgress())
            .laneChangeSourceIndex(v.getLaneChangeSourceIndex())
            .build();
    }

    /**
     * Maps a domain Obstacle to an ObstacleDto with domain coordinates.
     */
    ObstacleDto buildObstacleDto(Obstacle obs, Road road, int laneIndex) {
        return ObstacleDto.builder()
            .id(obs.getId())
            .roadId(road.getId())
            .laneId(obs.getLaneId())
            .laneIndex(laneIndex)
            .position(obs.getPosition())
            .build();
    }
}
