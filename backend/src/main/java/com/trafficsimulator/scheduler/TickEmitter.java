package com.trafficsimulator.scheduler;

import com.trafficsimulator.dto.ObstacleDto;
import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.dto.StatsDto;
import com.trafficsimulator.dto.TrafficLightDto;
import com.trafficsimulator.dto.VehicleDto;
import com.trafficsimulator.engine.IIntersectionManager;
import com.trafficsimulator.engine.ILaneChangeEngine;
import com.trafficsimulator.engine.IPhysicsEngine;
import com.trafficsimulator.engine.ITrafficLightController;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.SimulationStatus;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.trafficsimulator.engine.StatePublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickEmitter {

    /**
     * Pixel width per lane used for canvas Y-offset calculation.
     * NOTE: This couples the backend to the frontend pixel layout.
     * Moving pixel projection to the frontend (using roadId + laneId + position)
     * would be cleaner long-term and eliminate this constant.
     */
    private static final double LANE_WIDTH_PX = 14.0;

    /** Warn threshold for slow ticks (ms) */
    private static final long TICK_WARN_MS = 40;

    private final StatePublisher statePublisher;
    private final SimulationEngine simulationEngine;
    private final IVehicleSpawner vehicleSpawner;
    private final IPhysicsEngine physicsEngine;
    private final ILaneChangeEngine laneChangeEngine;
    private final ITrafficLightController trafficLightController;
    private final IIntersectionManager intersectionManager;

    @Scheduled(fixedRate = 50)
    public void emitTick() {
        long tickStart = System.nanoTime();

        // 1. Drain pending commands
        simulationEngine.drainCommands();

        RoadNetwork network = simulationEngine.getRoadNetwork();

        // 2. Only advance tick counter when simulation is actively running
        long tick;
        if (simulationEngine.getStatus() == SimulationStatus.RUNNING) {
            tick = simulationEngine.getTickCounter().incrementAndGet();

            // Run simulation pipeline when network is loaded
            if (network != null) {
                double baseDt = 0.05; // 50ms = 1/20 Hz
                double multiplier = simulationEngine.getSpeedMultiplier();
                double effectiveDt = baseDt * multiplier;

                // Sub-stepping: keep each physics step <= baseDt for Euler stability
                int subSteps = Math.max(1, (int) Math.ceil(effectiveDt / baseDt));
                double stepDt = effectiveDt / subSteps;

                // 0. Advance traffic lights (before physics, so stop lines are current)
                trafficLightController.tick(effectiveDt, network);

                // 0b. Compute stop lines for red lights and box-blocking
                Map<String, Double> stopLines = intersectionManager.computeStopLines(network);

                // 1. Spawn (uses full effectiveDt for accumulator)
                vehicleSpawner.tick(effectiveDt, network, tick);

                // 2. Physics sub-steps (with stop lines)
                for (int step = 0; step < subSteps; step++) {
                    for (Road road : network.getRoads().values()) {
                        for (Lane lane : road.getLanes()) {
                            double stopLine = stopLines.getOrDefault(lane.getId(), -1.0);
                            physicsEngine.tick(lane, stepDt, stopLine);
                        }
                    }
                }

                // 3. Lane changes (intent -> resolve -> commit) — once per tick, not per sub-step
                laneChangeEngine.tick(network, tick);

                // 4. Intersection transfers (after physics, before despawn)
                intersectionManager.processTransfers(network, tick);

                // 5. Despawn (only EXIT-node roads)
                vehicleSpawner.despawnVehicles(network);
            }
        } else {
            tick = simulationEngine.getTickCounter().get();
        }

        // 3. Build snapshot and broadcast
        SimulationStateDto state = buildSnapshot(tick, network);
        statePublisher.broadcast(state);

        // 4. Tick duration monitoring — warn if tick logic exceeded threshold
        long elapsedMs = (System.nanoTime() - tickStart) / 1_000_000;
        if (elapsedMs > TICK_WARN_MS) {
            log.warn("Tick #{} took {}ms (threshold {}ms) — vehicles={}", tick, elapsedMs, TICK_WARN_MS,
                state.getVehicles().size());
        }

        if (tick % 100 == 0) {
            log.info("Tick #{} — status={}, vehicles={}", tick, state.getStatus(), state.getVehicles().size());
        }
    }

    private SimulationStateDto buildSnapshot(long tick, RoadNetwork network) {
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
                    for (Vehicle v : lane.getVehicles()) {
                        vehicleDtos.add(projectVehicle(v, road, laneIdx));
                        totalSpeed += v.getSpeed();
                        vehicleCount++;
                    }
                    for (Obstacle obs : lane.getObstacles()) {
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
            .status(simulationEngine.getStatus().name())
            .vehicles(vehicleDtos)
            .obstacles(obstacleDtos)
            .trafficLights(trafficLightDtos)
            .stats(stats)
            .build();
    }

    /**
     * Projects a domain Obstacle to an ObstacleDto with pixel coordinates.
     */
    private ObstacleDto projectObstacle(Obstacle obs, Road road, int laneIndex) {
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

    /**
     * Projects a domain Vehicle to a VehicleDto with pixel coordinates.
     * x = startX + (position/length) * (endX - startX)
     * y = startY + laneOffset (centered, lane 0 topmost)
     * angle = atan2(endY - startY, endX - startX)
     */
    private VehicleDto projectVehicle(Vehicle v, Road road, int laneIndex) {
        double fraction = v.getPosition() / road.getLength();
        double x = road.getStartX() + fraction * (road.getEndX() - road.getStartX());
        double yBase = road.getStartY() + fraction * (road.getEndY() - road.getStartY());

        // Compute target lane y-offset (current lane)
        double targetLaneOffset = (laneIndex - (road.getLanes().size() - 1) / 2.0) * LANE_WIDTH_PX;

        double y;
        String targetLaneId = null;
        double lcProgress = 1.0;

        if (v.getLaneChangeSourceIndex() >= 0 && v.getLaneChangeProgress() < 1.0) {
            // Mid lane-change: interpolate between source and target lane y
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
}
