package com.trafficsimulator.scheduler;

import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.dto.StatsDto;
import com.trafficsimulator.dto.VehicleDto;
import com.trafficsimulator.engine.PhysicsEngine;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.SimulationStatus;
import com.trafficsimulator.engine.VehicleSpawner;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    private final SimpMessagingTemplate messagingTemplate;
    private final SimulationEngine simulationEngine;
    private final VehicleSpawner vehicleSpawner;
    private final PhysicsEngine physicsEngine;

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

            // Run spawn + despawn when network is loaded
            if (network != null) {
                vehicleSpawner.tick(0.05, network, tick);
                vehicleSpawner.despawnVehicles(network);
            }
        } else {
            tick = simulationEngine.getTickCounter().get();
        }

        // 3. Build snapshot and broadcast
        SimulationStateDto state = buildSnapshot(tick, network);
        messagingTemplate.convertAndSend("/topic/simulation", state);

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
            .throughput(0.0) // throughput tracking deferred to Phase 5
            .build();

        return SimulationStateDto.builder()
            .tick(tick)
            .timestamp(System.currentTimeMillis())
            .status(simulationEngine.getStatus().name())
            .vehicles(vehicleDtos)
            .stats(stats)
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
        double laneOffset = (laneIndex - (road.getLanes().size() - 1) / 2.0) * LANE_WIDTH_PX;
        double y = yBase + laneOffset;
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
            .build();
    }
}
