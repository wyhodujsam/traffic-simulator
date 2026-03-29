package com.trafficsimulator.engine;

import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@Slf4j
public class SimulationEngine {

    private final LinkedBlockingQueue<SimulationCommand> commandQueue =
        new LinkedBlockingQueue<>();

    private final ReentrantReadWriteLock networkLock = new ReentrantReadWriteLock();

    /** Returns the read lock for read-only access to road network state. */
    public ReentrantReadWriteLock.ReadLock readLock() {
        return networkLock.readLock();
    }

    /** Returns the write lock for mutating road network state. */
    public ReentrantReadWriteLock.WriteLock writeLock() {
        return networkLock.writeLock();
    }

    @Getter @Setter
    private volatile SimulationStatus status = SimulationStatus.STOPPED;

    @Getter
    private volatile RoadNetwork roadNetwork;

    @Getter
    private final AtomicLong tickCounter = new AtomicLong(0);

    /** Stored spawn rate — applied to VehicleSpawner when SetSpawnRate command is processed */
    @Getter @Setter
    private volatile double spawnRate = 1.0;

    /** Stored speed multiplier — read by tick loop in Phase 4 */
    @Getter @Setter
    private volatile double speedMultiplier = 1.0;

    /** Global max speed in m/s (~120 km/h default) */
    @Getter @Setter
    private volatile double maxSpeed = 33.33;

    @Autowired(required = false)
    private MapLoader mapLoader;

    @Autowired @Lazy
    private CommandDispatcher commandDispatcher;

    @PostConstruct
    void loadDefaultMap() {
        if (mapLoader == null) {
            log.warn("MapLoader not available — skipping default map load");
            return;
        }
        try {
            RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");
            setRoadNetwork(network);
            log.info("Default map loaded: {}", network.getId());
        } catch (Exception e) {
            log.error("Failed to load default map: {}", e.getMessage(), e);
        }
    }

    public void enqueue(SimulationCommand command) {
        commandQueue.offer(command);
    }

    /**
     * Called each tick BEFORE any simulation logic.
     * Drains all pending commands and dispatches them via CommandDispatcher.
     * Acquires writeLock internally — use when caller does NOT already hold the lock.
     */
    public void drainCommands() {
        List<SimulationCommand> pending = new ArrayList<>();
        commandQueue.drainTo(pending);
        if (pending.isEmpty()) return;

        writeLock().lock();
        try {
            for (SimulationCommand cmd : pending) {
                commandDispatcher.dispatch(cmd);
            }
        } finally {
            writeLock().unlock();
        }
    }

    /**
     * Drains pending commands WITHOUT acquiring the lock.
     * Use when the caller already holds writeLock (e.g., TickEmitter).
     */
    public void drainCommandsUnlocked() {
        List<SimulationCommand> pending = new ArrayList<>();
        commandQueue.drainTo(pending);
        for (SimulationCommand cmd : pending) {
            commandDispatcher.dispatch(cmd);
        }
    }

    public void setRoadNetwork(RoadNetwork roadNetwork) {
        this.roadNetwork = roadNetwork;
    }

    /**
     * Removes all vehicles from all lanes in the current road network.
     * Called on Stop to ensure a clean restart.
     * Package-private so CommandDispatcher can access it.
     */
    void clearAllVehicles() {
        if (roadNetwork == null) return;
        for (Road road : roadNetwork.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                lane.clearVehicles();
                lane.clearObstacles();
                lane.setActive(true);  // Reset lane status on stop
            }
        }
    }
}
