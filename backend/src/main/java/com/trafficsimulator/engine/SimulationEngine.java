package com.trafficsimulator.engine;

import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.RoadNetwork;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class SimulationEngine {

    private final LinkedBlockingQueue<SimulationCommand> commandQueue =
        new LinkedBlockingQueue<>();

    @Getter
    private volatile SimulationStatus status = SimulationStatus.STOPPED;

    @Getter
    private volatile RoadNetwork roadNetwork;

    @Getter
    private final AtomicLong tickCounter = new AtomicLong(0);

    /** Stored spawn rate — applied to VehicleSpawner when SetSpawnRate command is processed */
    @Getter
    private volatile double spawnRate = 1.0;

    /** Stored speed multiplier — read by tick loop in Phase 4 */
    @Getter
    private volatile double speedMultiplier = 1.0;

    // Optional — wired lazily to break circular dependency
    @Autowired(required = false)
    private VehicleSpawner vehicleSpawner;

    public void enqueue(SimulationCommand command) {
        commandQueue.offer(command);
    }

    /**
     * Called each tick BEFORE any simulation logic.
     * Drains all pending commands and applies them on the tick thread.
     */
    public void drainCommands() {
        List<SimulationCommand> pending = new ArrayList<>();
        commandQueue.drainTo(pending);
        for (SimulationCommand cmd : pending) {
            applyCommand(cmd);
        }
    }

    private void applyCommand(SimulationCommand cmd) {
        if (cmd instanceof SimulationCommand.Start) {
            if (status != SimulationStatus.STOPPED) {
                log.warn("Start command ignored: simulation is {} (expected STOPPED)", status);
                return;
            }
            status = SimulationStatus.RUNNING;
            log.info("Simulation started");

        } else if (cmd instanceof SimulationCommand.Stop) {
            if (status == SimulationStatus.STOPPED) {
                log.warn("Stop command ignored: simulation is already STOPPED");
                return;
            }
            status = SimulationStatus.STOPPED;
            tickCounter.set(0);
            log.info("Simulation stopped");

        } else if (cmd instanceof SimulationCommand.Pause) {
            if (status != SimulationStatus.RUNNING) {
                log.warn("Pause command ignored: simulation is {} (expected RUNNING)", status);
                return;
            }
            status = SimulationStatus.PAUSED;
            log.info("Simulation paused");

        } else if (cmd instanceof SimulationCommand.Resume) {
            if (status != SimulationStatus.PAUSED) {
                log.warn("Resume command ignored: simulation is {} (expected PAUSED)", status);
                return;
            }
            status = SimulationStatus.RUNNING;
            log.info("Simulation resumed");

        } else if (cmd instanceof SimulationCommand.SetSpawnRate setSpawnRate) {
            this.spawnRate = setSpawnRate.vehiclesPerSecond();
            // Wire through to VehicleSpawner so the rate change actually takes effect
            if (vehicleSpawner != null) {
                vehicleSpawner.setVehiclesPerSecond(this.spawnRate);
            }
            log.info("Spawn rate updated to {} veh/s", this.spawnRate);

        } else if (cmd instanceof SimulationCommand.SetSpeedMultiplier setSpeedMultiplier) {
            this.speedMultiplier = setSpeedMultiplier.multiplier();
            log.info("Speed multiplier updated to {}", this.speedMultiplier);

        } else if (cmd instanceof SimulationCommand.LoadMap loadMap) {
            log.info("Load map requested: {}", loadMap.mapId());

        } else {
            log.warn("Unhandled command type: {}", cmd.getClass().getSimpleName());
        }

        log.debug("Applied command: {}", cmd);
    }

    public void setRoadNetwork(RoadNetwork roadNetwork) {
        this.roadNetwork = roadNetwork;
    }
}
