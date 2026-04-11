package com.trafficsimulator.scheduler;

import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.engine.IIntersectionManager;
import com.trafficsimulator.engine.ILaneChangeEngine;
import com.trafficsimulator.engine.IPhysicsEngine;
import com.trafficsimulator.engine.ITrafficLightController;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.SimulationStatus;
import com.trafficsimulator.engine.StatePublisher;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickEmitter {

    /** Warn threshold for slow ticks (ms) */
    private static final long TICK_WARN_MS = 40;

    private final StatePublisher statePublisher;
    private final SimulationEngine simulationEngine;
    private final IVehicleSpawner vehicleSpawner;
    private final IPhysicsEngine physicsEngine;
    private final ILaneChangeEngine laneChangeEngine;
    private final ITrafficLightController trafficLightController;
    private final IIntersectionManager intersectionManager;
    private final SnapshotBuilder snapshotBuilder;

    @Scheduled(fixedRate = 50)
    public void emitTick() {
        long tickStart = System.nanoTime();

        // Acquire writeLock for the entire tick pipeline (drain + physics + snapshot)
        // to prevent concurrent modification from STOMP command threads.
        simulationEngine.writeLock().lock();
        try {
            // 1. Drain pending commands (no longer needs its own lock — we already hold writeLock)
            simulationEngine.drainCommandsUnlocked();

            RoadNetwork network = simulationEngine.getRoadNetwork();

            // 2. Only advance tick counter when simulation is actively running
            long tick;
            if (simulationEngine.getStatus() == SimulationStatus.RUNNING) {
                tick = simulationEngine.getTickCounter().incrementAndGet();
                runSimulationPipeline(network, tick);
            } else {
                tick = simulationEngine.getTickCounter().get();
            }

            // 3. Build snapshot and broadcast (under lock to read consistent state)
            String mapId = network != null ? network.getId() : null;
            String error = simulationEngine.getLastError();
            SimulationStateDto state =
                    snapshotBuilder.buildSnapshot(
                            network,
                            tick,
                            simulationEngine.getStatus().name(),
                            vehicleSpawner,
                            mapId,
                            error);
            statePublisher.broadcast(state);

            // Clear error after publishing once
            if (error != null) {
                simulationEngine.setLastError(null);
            }

            logTickMetrics(tick, tickStart, state);
        } finally {
            simulationEngine.writeLock().unlock();
        }
    }

    private void runSimulationPipeline(RoadNetwork network, long tick) {
        if (network == null) {
            return;
        }

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

    private void logTickMetrics(long tick, long tickStart, SimulationStateDto state) {
        long elapsedMs = (System.nanoTime() - tickStart) / 1_000_000;
        if (elapsedMs > TICK_WARN_MS) {
            log.warn(
                    "Tick #{} took {}ms (threshold {}ms) — vehicles={}",
                    tick,
                    elapsedMs,
                    TICK_WARN_MS,
                    state.getVehicles().size());
        }
        if (tick % 100 == 0) {
            log.info(
                    "Tick #{} — status={}, vehicles={}",
                    tick,
                    state.getStatus(),
                    state.getVehicles().size());
        }
    }
}
