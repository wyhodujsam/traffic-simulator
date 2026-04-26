package com.trafficsimulator.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * Master RNG algorithm name. Verified via Oracle Javadoc + jshell on Java 17.0.18 (RESEARCH.md
     * §"Verified seedable PRNG construction"). The {@link RngBootstrapTest} guards against typos
     * by asserting {@code RandomGeneratorFactory.of(MASTER_ALGORITHM)} resolves at every build —
     * mitigates threat T-25-04 (RNG factory crash on first user click).
     */
    public static final String MASTER_ALGORITHM = "L64X128MixRandom";

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

    /**
     * The seed source recorded by the last {@link #resolveSeedAndStart(Long)} call. One of {@code
     * "command"}, {@code "json"}, {@code "auto"}. Useful for tests and the future replay-log header
     * (D-04 / D-14). {@code null} until the first start.
     */
    @Getter private volatile String lastSeedSource;

    /**
     * The numeric seed resolved by the last {@link #resolveSeedAndStart(Long)} call. Useful for
     * tests and the replay-log header. {@code 0L} until the first start.
     */
    @Getter private volatile long lastResolvedSeed;

    /** Master RNG owned by the engine. Re-seeded on every {@code Start}. */
    @Getter private RandomGenerator.SplittableGenerator masterRng;

    /**
     * Sub-RNG split #1 — reserved for future per-spawn jitter. Registered FIRST in the D-02
     * append-only spawn order so future consumers do not reshuffle existing streams.
     */
    @Getter private RandomGenerator spawnerRng;

    /** Sub-RNG split #2 — drives intersection routing decisions in {@link IntersectionManager}. */
    @Getter private RandomGenerator ixtnRoutingRng;

    /** Sub-RNG split #3 — drives IDM ±20% noise in {@link VehicleSpawner}. */
    @Getter private RandomGenerator idmNoiseRng;

    private final MapLoader mapLoader;
    private CommandDispatcher commandDispatcher;
    private final VehicleSpawner vehicleSpawnerConcrete;
    private final IntersectionManager intersectionManagerConcrete;

    /**
     * Convenience constructor for tests/non-Spring callers that do not need to wire concrete
     * spawner/intersection beans (sub-RNG injection becomes a no-op).
     */
    public SimulationEngine(
            @Nullable MapLoader mapLoader, @Lazy CommandDispatcher commandDispatcher) {
        this(mapLoader, commandDispatcher, null, null);
    }

    /**
     * Spring-preferred constructor (annotated {@code @Autowired}). Receives the concrete {@link
     * VehicleSpawner} and {@link IntersectionManager} beans so {@link #resolveSeedAndStart(Long)}
     * can call their package-private {@code setRng} methods (D-02 / D-03 wiring).
     */
    @Autowired
    public SimulationEngine(
            @Nullable MapLoader mapLoader,
            @Lazy CommandDispatcher commandDispatcher,
            @Nullable VehicleSpawner vehicleSpawnerConcrete,
            @Nullable IntersectionManager intersectionManagerConcrete) {
        this.mapLoader = mapLoader;
        this.commandDispatcher = commandDispatcher;
        this.vehicleSpawnerConcrete = vehicleSpawnerConcrete;
        this.intersectionManagerConcrete = intersectionManagerConcrete;
    }

    /**
     * Resolves the master RNG seed per D-01 precedence ({@code command > json > nanoTime}), spawns
     * 3 sub-RNGs in the fixed D-02 spawn order ({@code spawnerRng → ixtnRoutingRng → idmNoiseRng};
     * append-only), injects the routing RNG into {@link IntersectionManager} and the IDM-noise RNG
     * into {@link VehicleSpawner}, then emits the D-04 INFO log line. Idempotent — call on every
     * {@code Start}.
     *
     * @param commandSeed optional STOMP {@code Start.seed} (highest precedence; nullable)
     */
    public void resolveSeedAndStart(Long commandSeed) {
        Long jsonSeed = (roadNetwork != null) ? roadNetwork.getSeed() : null;
        long resolvedSeed;
        String source;
        if (commandSeed != null) {
            resolvedSeed = commandSeed;
            source = "command";
        } else if (jsonSeed != null) {
            resolvedSeed = jsonSeed;
            source = "json";
        } else {
            resolvedSeed = System.nanoTime();
            source = "auto";
        }

        this.lastResolvedSeed = resolvedSeed;
        this.lastSeedSource = source;

        log.info("[SimulationEngine] Started with seed={} source={}", resolvedSeed, source);

        RandomGeneratorFactory<RandomGenerator> factory = RandomGeneratorFactory.of(MASTER_ALGORITHM);
        this.masterRng = (RandomGenerator.SplittableGenerator) factory.create(resolvedSeed);
        // FIXED ORDER per D-02 — append-only. Adding a new consumer MUST append a new
        // master.split() at the END of this list. Inserting in the middle reshuffles every
        // existing seeded stream and breaks DET-05 byte-identity.
        this.spawnerRng = masterRng.split();
        this.ixtnRoutingRng = masterRng.split();
        this.idmNoiseRng = masterRng.split();

        if (vehicleSpawnerConcrete != null) {
            vehicleSpawnerConcrete.setRng(idmNoiseRng);
        }
        if (intersectionManagerConcrete != null) {
            intersectionManagerConcrete.setRng(ixtnRoutingRng);
        }
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
