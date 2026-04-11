package com.trafficsimulator.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SimulationEngine {

    private final BlockingQueue<SimulationCommand> commandQueue = new LinkedBlockingQueue<>();

    private final ReentrantReadWriteLock networkLock = new ReentrantReadWriteLock();

    @Getter @Setter private volatile SimulationStatus status = SimulationStatus.STOPPED;

    @Getter
    @SuppressWarnings(
            "java:S3077") // RoadNetwork is immutable after construction, volatile swap is safe
    private volatile RoadNetwork roadNetwork;

    @Getter private final AtomicLong tickCounter = new AtomicLong(0);

    /** Stored spawn rate — applied to VehicleSpawner when SetSpawnRate command is processed */
    @Getter @Setter private volatile double spawnRate = 1.0;

    /** Stored speed multiplier — read by tick loop in Phase 4 */
    @Getter @Setter private volatile double speedMultiplier = 1.0;

    /** Global max speed in m/s (~120 km/h default) */
    @Getter @Setter private volatile double maxSpeed = 33.33;

    /** Last error message (e.g. failed LOAD_MAP), published once then cleared */
    @Getter @Setter private volatile String lastError;

    private final MapLoader mapLoader;
    private CommandDispatcher commandDispatcher;

    public SimulationEngine(
            @Nullable MapLoader mapLoader, @Lazy CommandDispatcher commandDispatcher) {
        this.mapLoader = mapLoader;
        this.commandDispatcher = commandDispatcher;
    }

    /** Returns the read lock for read-only access to road network state. */
    public ReentrantReadWriteLock.ReadLock readLock() {
        return networkLock.readLock();
    }

    /** Returns the write lock for mutating road network state. */
    public ReentrantReadWriteLock.WriteLock writeLock() {
        return networkLock.writeLock();
    }

    void setCommandDispatcher(CommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    @PostConstruct
    void loadDefaultMap() {
        if (mapLoader == null) {
            log.warn("MapLoader not available — skipping default map load");
            return;
        }
        try {
            MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/straight-road.json");
            setRoadNetwork(loaded.network());
            log.info("Default map loaded: {}", loaded.network().getId());
        } catch (IOException | IllegalArgumentException e) {
            log.error("Failed to load default map: {}", e.getMessage(), e);
        }
    }

    public void enqueue(SimulationCommand command) {
        if (!commandQueue.offer(command)) {
            log.warn(
                    "Command queue full, dropping command: {}", command.getClass().getSimpleName());
        }
    }

    /**
     * Called each tick BEFORE any simulation logic. Drains all pending commands and dispatches them
     * via CommandDispatcher. Acquires writeLock internally — use when caller does NOT already hold
     * the lock.
     */
    public void drainCommands() {
        List<SimulationCommand> pending = new ArrayList<>();
        commandQueue.drainTo(pending);
        if (pending.isEmpty()) {
            return;
        }

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
     * Drains pending commands WITHOUT acquiring the lock. Use when the caller already holds
     * writeLock (e.g., TickEmitter).
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
     * Removes all vehicles from all lanes in the current road network. Called on Stop to ensure a
     * clean restart. Package-private so CommandDispatcher can access it.
     */
    void clearAllVehicles() {
        if (roadNetwork == null) {
            return;
        }
        for (Road road : roadNetwork.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                lane.clearVehicles();
                lane.clearObstacles();
                lane.setActive(true); // Reset lane status on stop
            }
        }
    }
}
