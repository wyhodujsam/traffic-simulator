package com.trafficsimulator.engine;

import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.TrafficLightPhase;
import com.trafficsimulator.model.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dispatches simulation commands to the appropriate handler methods.
 * Extracted from SimulationEngine to separate command processing from state management.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommandDispatcher {

    private final SimulationEngine engine;

    @Autowired(required = false)
    private VehicleSpawner vehicleSpawner;

    @Autowired(required = false)
    private ObstacleManager obstacleManager;

    @Autowired(required = false)
    private MapLoader mapLoader;

    public void dispatch(SimulationCommand cmd) {
        if (cmd instanceof SimulationCommand.Start) {
            handleStart();
        } else if (cmd instanceof SimulationCommand.Stop) {
            handleStop();
        } else if (cmd instanceof SimulationCommand.Pause) {
            handlePause();
        } else if (cmd instanceof SimulationCommand.Resume) {
            handleResume();
        } else if (cmd instanceof SimulationCommand.SetSpawnRate setSpawnRate) {
            handleSetSpawnRate(setSpawnRate);
        } else if (cmd instanceof SimulationCommand.SetSpeedMultiplier setSpeedMultiplier) {
            handleSetSpeedMultiplier(setSpeedMultiplier);
        } else if (cmd instanceof SimulationCommand.SetMaxSpeed setMaxSpeed) {
            handleSetMaxSpeed(setMaxSpeed);
        } else if (cmd instanceof SimulationCommand.AddObstacle addObs) {
            handleAddObstacle(addObs);
        } else if (cmd instanceof SimulationCommand.RemoveObstacle removeObs) {
            handleRemoveObstacle(removeObs);
        } else if (cmd instanceof SimulationCommand.CloseLane closeLane) {
            handleCloseLane(closeLane);
        } else if (cmd instanceof SimulationCommand.SetLightCycle slc) {
            handleSetLightCycle(slc);
        } else if (cmd instanceof SimulationCommand.LoadMap loadMap) {
            handleLoadMap(loadMap);
        } else {
            log.warn("Unhandled command type: {}", cmd.getClass().getSimpleName());
        }

        log.debug("Applied command: {}", cmd);
    }

    private void handleStart() {
        if (engine.getStatus() != SimulationStatus.STOPPED) {
            log.warn("Start command ignored: simulation is {} (expected STOPPED)", engine.getStatus());
            return;
        }
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
            log.warn("Pause command ignored: simulation is {} (expected RUNNING)", engine.getStatus());
            return;
        }
        engine.setStatus(SimulationStatus.PAUSED);
        log.info("Simulation paused");
    }

    private void handleResume() {
        if (engine.getStatus() != SimulationStatus.PAUSED) {
            log.warn("Resume command ignored: simulation is {} (expected PAUSED)", engine.getStatus());
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
            obstacleManager.addObstacle(roadNetwork, cmd.roadId(), cmd.laneIndex(),
                cmd.position(), engine.getTickCounter().get());
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
        if (roadNetwork == null) return;

        Road road = roadNetwork.getRoads().get(cmd.roadId());
        if (road != null && cmd.laneIndex() >= 0 && cmd.laneIndex() < road.getLanes().size()) {
            Lane lane = road.getLanes().get(cmd.laneIndex());

            // Count active lanes — don't close the last one
            long activeLanes = road.getLanes().stream().filter(Lane::isActive).count();
            if (activeLanes <= 1) {
                log.warn("Cannot close lane {} — it's the last active lane on road {}",
                    cmd.laneIndex(), cmd.roadId());
                return;
            }

            lane.setActive(false);

            // Flag all vehicles in the closed lane for forced lane change
            for (Vehicle v : lane.getVehiclesView()) {
                v.setForceLaneChange(true);
            }

            log.info("Lane closed: road={} lane={} — {} vehicles flagged for merge",
                cmd.roadId(), cmd.laneIndex(), lane.getVehicleCount());
        } else {
            log.warn("Cannot close lane: road={} laneIndex={} not found",
                cmd.roadId(), cmd.laneIndex());
        }
    }

    private void handleSetLightCycle(SimulationCommand.SetLightCycle cmd) {
        RoadNetwork roadNetwork = engine.getRoadNetwork();
        if (roadNetwork == null) return;

        Intersection ixtn = roadNetwork.getIntersections().get(cmd.intersectionId());
        if (ixtn != null && ixtn.getTrafficLight() != null) {
            TrafficLight tl = ixtn.getTrafficLight();
            List<TrafficLightPhase> newPhases = new ArrayList<>();
            Set<Set<String>> seenGroups = new LinkedHashSet<>();
            for (TrafficLightPhase p : tl.getPhases()) {
                if (p.getType() == TrafficLightPhase.PhaseType.GREEN) {
                    if (seenGroups.add(p.getGreenRoadIds())) {
                        newPhases.add(TrafficLightPhase.builder()
                            .greenRoadIds(p.getGreenRoadIds())
                            .durationMs(cmd.greenDurationMs())
                            .type(TrafficLightPhase.PhaseType.GREEN).build());
                        newPhases.add(TrafficLightPhase.builder()
                            .greenRoadIds(p.getGreenRoadIds())
                            .durationMs(cmd.yellowDurationMs())
                            .type(TrafficLightPhase.PhaseType.YELLOW).build());
                        newPhases.add(TrafficLightPhase.builder()
                            .greenRoadIds(Set.of())
                            .durationMs(2000)
                            .type(TrafficLightPhase.PhaseType.ALL_RED).build());
                    }
                }
            }
            tl.replacePhases(newPhases);
            log.info("Signal timing updated: intersection={} green={}ms yellow={}ms",
                cmd.intersectionId(), cmd.greenDurationMs(), cmd.yellowDurationMs());
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
        if (obstacleManager != null) {
            RoadNetwork oldNetwork = engine.getRoadNetwork();
            if (oldNetwork != null) {
                obstacleManager.clearAll(oldNetwork);
            }
        }
        if (vehicleSpawner != null) {
            vehicleSpawner.reset();
        }

        try {
            MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/" + cmd.mapId() + ".json");
            engine.setRoadNetwork(loaded.network());
            // Apply default spawn rate from map config
            engine.setSpawnRate(loaded.defaultSpawnRate());
            if (vehicleSpawner != null) {
                vehicleSpawner.setVehiclesPerSecond(loaded.defaultSpawnRate());
            }
            engine.setLastError(null); // clear any previous error
            log.info("Map loaded: {} (spawn rate: {} veh/s)", cmd.mapId(), loaded.defaultSpawnRate());
        } catch (Exception e) {
            log.error("Failed to load map {}: {}", cmd.mapId(), e.getMessage());
            engine.setLastError("Failed to load map '" + cmd.mapId() + "': " + e.getMessage());
        }
    }
}
