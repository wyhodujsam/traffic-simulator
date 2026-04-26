package com.trafficsimulator.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.TrafficLightPhase;
import com.trafficsimulator.model.Vehicle;

import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches simulation commands to the appropriate handler methods. Extracted from
 * SimulationEngine to separate command processing from state management.
 */
@Component
@Slf4j
public class CommandDispatcher {

    private static final long MIN_ACTIVE_LANES = 1;

    private final SimulationEngine engine;
    private final VehicleSpawner vehicleSpawner;
    private final ObstacleManager obstacleManager;
    private final MapLoader mapLoader;

    private final Map<Class<? extends SimulationCommand>, Consumer<SimulationCommand>> handlers =
            new LinkedHashMap<>();

    public CommandDispatcher(
            SimulationEngine engine,
            @Nullable VehicleSpawner vehicleSpawner,
            @Nullable ObstacleManager obstacleManager,
            @Nullable MapLoader mapLoader) {
        this.engine = engine;
        this.vehicleSpawner = vehicleSpawner;
        this.obstacleManager = obstacleManager;
        this.mapLoader = mapLoader;
        registerHandlers();
    }

    private void registerHandlers() {
        handlers.put(
                SimulationCommand.Start.class,
                cmd -> handleStart((SimulationCommand.Start) cmd));
        handlers.put(SimulationCommand.Stop.class, cmd -> handleStop());
        handlers.put(SimulationCommand.Pause.class, cmd -> handlePause());
        handlers.put(SimulationCommand.Resume.class, cmd -> handleResume());
        handlers.put(
                SimulationCommand.SetSpawnRate.class,
                cmd -> handleSetSpawnRate((SimulationCommand.SetSpawnRate) cmd));
        handlers.put(
                SimulationCommand.SetSpeedMultiplier.class,
                cmd -> handleSetSpeedMultiplier((SimulationCommand.SetSpeedMultiplier) cmd));
        handlers.put(
                SimulationCommand.SetMaxSpeed.class,
                cmd -> handleSetMaxSpeed((SimulationCommand.SetMaxSpeed) cmd));
        handlers.put(
                SimulationCommand.AddObstacle.class,
                cmd -> handleAddObstacle((SimulationCommand.AddObstacle) cmd));
        handlers.put(
                SimulationCommand.RemoveObstacle.class,
                cmd -> handleRemoveObstacle((SimulationCommand.RemoveObstacle) cmd));
        handlers.put(
                SimulationCommand.CloseLane.class,
                cmd -> handleCloseLane((SimulationCommand.CloseLane) cmd));
        handlers.put(
                SimulationCommand.SetLightCycle.class,
                cmd -> handleSetLightCycle((SimulationCommand.SetLightCycle) cmd));
        handlers.put(
                SimulationCommand.LoadMap.class,
                cmd -> handleLoadMap((SimulationCommand.LoadMap) cmd));
        handlers.put(
                SimulationCommand.LoadConfig.class,
                cmd -> handleLoadConfig((SimulationCommand.LoadConfig) cmd));
    }

    public void dispatch(SimulationCommand cmd) {
        Consumer<SimulationCommand> handler = handlers.get(cmd.getClass());
        if (handler != null) {
            handler.accept(cmd);
        } else {
            log.warn("Unhandled command type: {}", cmd.getClass().getSimpleName());
        }

        log.debug("Applied command: {}", cmd);
    }

    private void handleStart(SimulationCommand.Start cmd) {
        if (engine.getStatus() != SimulationStatus.STOPPED) {
            log.warn(
                    "Start command ignored: simulation is {} (expected STOPPED)",
                    engine.getStatus());
            return;
        }
        // D-01..D-04: resolve seed precedence, fan master into 3 sub-RNGs (D-02 spawn order),
        // inject into spawner + intersection manager, emit INFO log line. Must run BEFORE the
        // status flip so RNGs are ready when the first tick fires.
        engine.resolveSeedAndStart(cmd.seed());
        engine.setStatus(SimulationStatus.RUNNING);
        log.info("Simulation started");
    }

    private void handleStop() {
        if (engine.getStatus() == SimulationStatus.STOPPED) {
            log.warn("Stop command ignored: simulation is already STOPPED");
            return;
        }
        engine.setStatus(SimulationStatus.STOPPED);
        engine.getTickCounter().set(0);
        engine.clearAllVehicles();
        if (vehicleSpawner != null) {
            vehicleSpawner.reset();
        }
        log.info("Simulation stopped — state cleared");
    }

    private void handlePause() {
        if (engine.getStatus() != SimulationStatus.RUNNING) {
            log.warn(
                    "Pause command ignored: simulation is {} (expected RUNNING)",
                    engine.getStatus());
            return;
        }
        engine.setStatus(SimulationStatus.PAUSED);
        log.info("Simulation paused");
    }

    private void handleResume() {
        if (engine.getStatus() != SimulationStatus.PAUSED) {
            log.warn(
                    "Resume command ignored: simulation is {} (expected PAUSED)",
                    engine.getStatus());
            return;
        }
        engine.setStatus(SimulationStatus.RUNNING);
        log.info("Simulation resumed");
    }

    private void handleSetSpawnRate(SimulationCommand.SetSpawnRate cmd) {
        engine.setSpawnRate(cmd.vehiclesPerSecond());
        if (vehicleSpawner != null) {
            vehicleSpawner.setVehiclesPerSecond(cmd.vehiclesPerSecond());
        }
        log.info("Spawn rate updated to {} veh/s", cmd.vehiclesPerSecond());
    }

    private void handleSetSpeedMultiplier(SimulationCommand.SetSpeedMultiplier cmd) {
        engine.setSpeedMultiplier(cmd.multiplier());
        log.info("Speed multiplier updated to {}", cmd.multiplier());
    }

    private void handleSetMaxSpeed(SimulationCommand.SetMaxSpeed cmd) {
        engine.setMaxSpeed(cmd.maxSpeedMs());
        RoadNetwork roadNetwork = engine.getRoadNetwork();
        if (roadNetwork != null) {
            for (Road road : roadNetwork.getRoads().values()) {
                for (Lane lane : road.getLanes()) {
                    lane.setMaxSpeed(cmd.maxSpeedMs());
                }
            }
        }
        log.info("Max speed updated to {} m/s ({} km/h)", cmd.maxSpeedMs(), cmd.maxSpeedMs() * 3.6);
    }

    private void handleAddObstacle(SimulationCommand.AddObstacle cmd) {
        RoadNetwork roadNetwork = engine.getRoadNetwork();
        if (roadNetwork != null && obstacleManager != null) {
            obstacleManager.addObstacle(
                    roadNetwork,
                    cmd.roadId(),
                    cmd.laneIndex(),
                    cmd.position(),
                    engine.getTickCounter().get());
        }
    }

    private void handleRemoveObstacle(SimulationCommand.RemoveObstacle cmd) {
        RoadNetwork roadNetwork = engine.getRoadNetwork();
        if (roadNetwork != null && obstacleManager != null) {
            obstacleManager.removeObstacle(roadNetwork, cmd.obstacleId());
        }
    }

    private void handleCloseLane(SimulationCommand.CloseLane cmd) {
        RoadNetwork roadNetwork = engine.getRoadNetwork();
        if (roadNetwork == null) {
            return;
        }

        Road road = roadNetwork.getRoads().get(cmd.roadId());
        if (road != null && cmd.laneIndex() >= 0 && cmd.laneIndex() < road.getLanes().size()) {
            Lane lane = road.getLanes().get(cmd.laneIndex());

            // Count active lanes — don't close the last one
            long activeLanes = road.getLanes().stream().filter(Lane::isActive).count();
            if (activeLanes <= MIN_ACTIVE_LANES) {
                log.warn(
                        "Cannot close lane {} — it's the last active lane on road {}",
                        cmd.laneIndex(),
                        cmd.roadId());
                return;
            }

            lane.setActive(false);

            // Flag all vehicles in the closed lane for forced lane change
            for (Vehicle v : lane.getVehiclesView()) {
                v.setForceLaneChange(true);
            }

            log.info(
                    "Lane closed: road={} lane={} — {} vehicles flagged for merge",
                    cmd.roadId(),
                    cmd.laneIndex(),
                    lane.getVehicleCount());
        } else {
            log.warn(
                    "Cannot close lane: road={} laneIndex={} not found",
                    cmd.roadId(),
                    cmd.laneIndex());
        }
    }

    private void handleSetLightCycle(SimulationCommand.SetLightCycle cmd) {
        RoadNetwork roadNetwork = engine.getRoadNetwork();
        if (roadNetwork == null) {
            return;
        }

        Intersection ixtn = roadNetwork.getIntersections().get(cmd.intersectionId());
        if (ixtn != null && ixtn.getTrafficLight() != null) {
            TrafficLight tl = ixtn.getTrafficLight();
            List<TrafficLightPhase> newPhases = new ArrayList<>();
            Set<Set<String>> seenGroups = new LinkedHashSet<>();
            for (TrafficLightPhase p : tl.getPhases()) {
                if (p.getType() == TrafficLightPhase.PhaseType.GREEN
                        && seenGroups.add(p.getGreenRoadIds())) {
                    newPhases.add(
                            TrafficLightPhase.builder()
                                    .greenRoadIds(p.getGreenRoadIds())
                                    .durationMs(cmd.greenDurationMs())
                                    .type(TrafficLightPhase.PhaseType.GREEN)
                                    .build());
                    newPhases.add(
                            TrafficLightPhase.builder()
                                    .greenRoadIds(p.getGreenRoadIds())
                                    .durationMs(cmd.yellowDurationMs())
                                    .type(TrafficLightPhase.PhaseType.YELLOW)
                                    .build());
                    newPhases.add(
                            TrafficLightPhase.builder()
                                    .greenRoadIds(Set.of())
                                    .durationMs(2000)
                                    .type(TrafficLightPhase.PhaseType.ALL_RED)
                                    .build());
                }
            }
            tl.replacePhases(newPhases);
            log.info(
                    "Signal timing updated: intersection={} green={}ms yellow={}ms",
                    cmd.intersectionId(),
                    cmd.greenDurationMs(),
                    cmd.yellowDurationMs());
        }
    }

    private void handleLoadMap(SimulationCommand.LoadMap cmd) {
        if (mapLoader == null) {
            log.warn("MapLoader not available — ignoring LoadMap command");
            return;
        }
        // Stop and clear
        engine.setStatus(SimulationStatus.STOPPED);
        engine.getTickCounter().set(0);
        engine.clearAllVehicles();
        RoadNetwork oldNetwork = engine.getRoadNetwork();
        if (obstacleManager != null && oldNetwork != null) {
            obstacleManager.clearAll(oldNetwork);
        }
        if (vehicleSpawner != null) {
            vehicleSpawner.reset();
        }

        try {
            MapLoader.LoadedMap loaded =
                    mapLoader.loadFromClasspath("maps/" + cmd.mapId() + ".json");
            engine.setRoadNetwork(loaded.network());
            // Apply default spawn rate from map config
            engine.setSpawnRate(loaded.defaultSpawnRate());
            if (vehicleSpawner != null) {
                vehicleSpawner.setVehiclesPerSecond(loaded.defaultSpawnRate());
            }
            engine.setLastError(null); // clear any previous error
            // Apply closed lanes from map config
            for (var road : loaded.network().getRoads().values()) {
                for (var lane : road.getLanes()) {
                    if (!lane.isActive()) {
                        log.info("Lane pre-closed by map config: {}", lane.getId());
                    }
                }
            }
            // Plan 25-03 Q2: prime any initialVehicles defined in the JSON onto their lanes
            primeInitialVehicles(loaded.network());
            log.info(
                    "Map loaded: {} (spawn rate: {} veh/s)",
                    cmd.mapId(),
                    loaded.defaultSpawnRate());
        } catch (IOException | IllegalArgumentException e) {
            log.error("Failed to load map {}: {}", cmd.mapId(), e.getMessage());
            engine.setLastError("Failed to load map '" + cmd.mapId() + "': " + e.getMessage());
        }
    }

    private void handleLoadConfig(SimulationCommand.LoadConfig cmd) {
        if (mapLoader == null) {
            log.warn("MapLoader not available — ignoring LoadConfig command");
            return;
        }
        // Stop and clear
        engine.setStatus(SimulationStatus.STOPPED);
        engine.getTickCounter().set(0);
        engine.clearAllVehicles();
        RoadNetwork oldNetwork = engine.getRoadNetwork();
        if (obstacleManager != null && oldNetwork != null) {
            obstacleManager.clearAll(oldNetwork);
        }
        if (vehicleSpawner != null) {
            vehicleSpawner.reset();
        }

        try {
            MapLoader.LoadedMap loaded = mapLoader.loadFromConfig(cmd.config());
            engine.setRoadNetwork(loaded.network());
            engine.setSpawnRate(loaded.defaultSpawnRate());
            if (vehicleSpawner != null) {
                vehicleSpawner.setVehiclesPerSecond(loaded.defaultSpawnRate());
            }
            engine.setLastError(null);
            // Plan 25-03 Q2: prime any initialVehicles defined in the JSON onto their lanes
            primeInitialVehicles(loaded.network());
            log.info(
                    "Map loaded from config: {} (spawn rate: {} veh/s)",
                    cmd.config().getId(),
                    loaded.defaultSpawnRate());
        } catch (IllegalArgumentException e) {
            log.error("Failed to load config {}: {}", cmd.config().getId(), e.getMessage());
            engine.setLastError(
                    "Failed to load config '" + cmd.config().getId() + "': " + e.getMessage());
        }
    }

    /**
     * Plan 25-03 Q2 — prime initialVehicles from a loaded scenario onto their lanes. Out-of-range
     * road/lane references are silently skipped (T-25-IV-01 mitigation in plan threat model).
     * Vehicles are inserted via the existing {@link Lane#addVehicle} API which preserves the
     * sorted-by-position invariant.
     */
    private void primeInitialVehicles(RoadNetwork network) {
        List<MapConfig.InitialVehicleConfig> initials = network.getInitialVehicles();
        if (initials == null || initials.isEmpty()) {
            return;
        }
        int primed = 0;
        for (MapConfig.InitialVehicleConfig iv : initials) {
            Road road = network.getRoads().get(iv.getRoadId());
            if (road == null) {
                log.debug(
                        "Skipping initial vehicle: road '{}' not found", iv.getRoadId());
                continue;
            }
            if (iv.getLaneIndex() < 0 || iv.getLaneIndex() >= road.getLanes().size()) {
                log.debug(
                        "Skipping initial vehicle: laneIndex {} out of range on road '{}'",
                        iv.getLaneIndex(),
                        iv.getRoadId());
                continue;
            }
            Lane lane = road.getLanes().get(iv.getLaneIndex());
            Vehicle v =
                    Vehicle.builder()
                            .id(java.util.UUID.randomUUID().toString())
                            .position(iv.getPosition())
                            .speed(iv.getSpeed())
                            .acceleration(0.0)
                            .lane(lane)
                            .length(4.5)
                            .v0(road.getSpeedLimit())
                            .aMax(1.4)
                            .b(2.0)
                            .s0(2.0)
                            .timeHeadway(1.5)
                            .spawnedAt(0L)
                            .build();
            lane.addVehicle(v);
            primed++;
        }
        log.info("[CommandDispatcher] Primed {} initial vehicles", primed);
    }
}
