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

    /** Pixel width per lane — temporary, removed when projection moves to frontend */
    private static final double LANE_WIDTH_PX = 14.0;

    /**
     * Builds a complete SimulationStateDto snapshot from the current state.
     */
    public SimulationStateDto buildSnapshot(RoadNetwork network, long tick,
            String status, double spawnRate, double speedMultiplier,
            IVehicleSpawner vehicleSpawner) {

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
                        vehicleDtos.add(projectVehicle(v, road, laneIdx));
                        totalSpeed += v.getSpeed();
                        vehicleCount++;
                    }
                    for (Obstacle obs : lane.getObstaclesView()) {
                        obstacleDtos.add(projectObstacle(obs, road, laneIdx));
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
            .build();
    }

    /**
     * Projects a domain Vehicle to a VehicleDto with pixel coordinates.
     */
    VehicleDto projectVehicle(Vehicle v, Road road, int laneIndex) {
        double fraction = v.getPosition() / road.getLength();
        double x = road.getStartX() + fraction * (road.getEndX() - road.getStartX());
        double yBase = road.getStartY() + fraction * (road.getEndY() - road.getStartY());

        double targetLaneOffset = (laneIndex - (road.getLanes().size() - 1) / 2.0) * LANE_WIDTH_PX;

        double y;
        String targetLaneId = null;
        double lcProgress = 1.0;

        if (v.getLaneChangeSourceIndex() >= 0 && v.getLaneChangeProgress() < 1.0) {
            double sourceLaneOffset = (v.getLaneChangeSourceIndex()
                - (road.getLanes().size() - 1) / 2.0) * LANE_WIDTH_PX;
            double progress = v.getLaneChangeProgress();
            y = yBase + sourceLaneOffset + progress * (targetLaneOffset - sourceLaneOffset);
            targetLaneId = v.getLane().getId();
            lcProgress = progress;
        } else {
            y = yBase + targetLaneOffset;
        }

        double angle = Math.atan2(road.getEndY() - road.getStartY(),
                                  road.getEndX() - road.getStartX());

        return VehicleDto.builder()
            .id(v.getId())
            .laneId(v.getLane().getId())
            .position(v.getPosition())
            .speed(v.getSpeed())
            .x(x)
            .y(y)
            .angle(angle)
            .targetLaneId(targetLaneId)
            .laneChangeProgress(lcProgress)
            .build();
    }

    /**
     * Projects a domain Obstacle to an ObstacleDto with pixel coordinates.
     */
    ObstacleDto projectObstacle(Obstacle obs, Road road, int laneIndex) {
        double fraction = obs.getPosition() / road.getLength();
        double x = road.getStartX() + fraction * (road.getEndX() - road.getStartX());
        double yBase = road.getStartY() + fraction * (road.getEndY() - road.getStartY());
        double laneOffset = (laneIndex - (road.getLanes().size() - 1) / 2.0) * LANE_WIDTH_PX;
        double y = yBase + laneOffset;
        double angle = Math.atan2(road.getEndY() - road.getStartY(),
                                  road.getEndX() - road.getStartX());

        return ObstacleDto.builder()
            .id(obs.getId())
            .laneId(obs.getLaneId())
            .position(obs.getPosition())
            .x(x)
            .y(y)
            .angle(angle)
            .build();
    }
}
