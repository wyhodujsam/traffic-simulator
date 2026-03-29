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
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /** Global max speed in m/s (~120 km/h default) */
    @Getter
    private volatile double maxSpeed = 33.33;

    // Optional — wired lazily to break circular dependency
    @Autowired(required = false)
    private VehicleSpawner vehicleSpawner;

    @Autowired(required = false)
    private ObstacleManager obstacleManager;

    @Autowired(required = false)
    private MapLoader mapLoader;

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
            clearAllVehicles();
            if (vehicleSpawner != null) {
                vehicleSpawner.reset();
            }
            log.info("Simulation stopped — state cleared");

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

        } else if (cmd instanceof SimulationCommand.SetMaxSpeed setMaxSpeed) {
            this.maxSpeed = setMaxSpeed.maxSpeedMs();
            if (roadNetwork != null) {
                for (Road road : roadNetwork.getRoads().values()) {
                    for (Lane lane : road.getLanes()) {
                        lane.setMaxSpeed(this.maxSpeed);
                    }
                }
            }
            log.info("Max speed updated to {} m/s ({} km/h)", this.maxSpeed, this.maxSpeed * 3.6);

        } else if (cmd instanceof SimulationCommand.AddObstacle addObs) {
            if (roadNetwork != null && obstacleManager != null) {
                obstacleManager.addObstacle(roadNetwork, addObs.roadId(), addObs.laneIndex(),
                    addObs.position(), tickCounter.get());
            }

        } else if (cmd instanceof SimulationCommand.RemoveObstacle removeObs) {
            if (roadNetwork != null && obstacleManager != null) {
                obstacleManager.removeObstacle(roadNetwork, removeObs.obstacleId());
            }

        } else if (cmd instanceof SimulationCommand.CloseLane closeLane) {
            if (roadNetwork != null) {
                Road road = roadNetwork.getRoads().get(closeLane.roadId());
                if (road != null && closeLane.laneIndex() >= 0
                    && closeLane.laneIndex() < road.getLanes().size()) {
                    Lane lane = road.getLanes().get(closeLane.laneIndex());

                    // Count active lanes — don't close the last one
                    long activeLanes = road.getLanes().stream().filter(Lane::isActive).count();
                    if (activeLanes <= 1) {
                        log.warn("Cannot close lane {} — it's the last active lane on road {}",
                            closeLane.laneIndex(), closeLane.roadId());
                        return;
                    }

                    lane.setActive(false);

                    // Flag all vehicles in the closed lane for forced lane change
                    for (Vehicle v : lane.getVehiclesView()) {
                        v.setForceLaneChange(true);
                    }

                    log.info("Lane closed: road={} lane={} — {} vehicles flagged for merge",
                        closeLane.roadId(), closeLane.laneIndex(), lane.getVehicleCount());
                } else {
                    log.warn("Cannot close lane: road={} laneIndex={} not found",
                        closeLane.roadId(), closeLane.laneIndex());
                }
            }

        } else if (cmd instanceof SimulationCommand.SetLightCycle slc) {
            if (roadNetwork != null) {
                Intersection ixtn = roadNetwork.getIntersections().get(slc.intersectionId());
                if (ixtn != null && ixtn.getTrafficLight() != null) {
                    TrafficLight tl = ixtn.getTrafficLight();
                    List<TrafficLightPhase> newPhases = new ArrayList<>();
                    Set<Set<String>> seenGroups = new LinkedHashSet<>();
                    for (TrafficLightPhase p : tl.getPhases()) {
                        if (p.getType() == TrafficLightPhase.PhaseType.GREEN) {
                            if (seenGroups.add(p.getGreenRoadIds())) {
                                newPhases.add(TrafficLightPhase.builder()
                                    .greenRoadIds(p.getGreenRoadIds())
                                    .durationMs(slc.greenDurationMs())
                                    .type(TrafficLightPhase.PhaseType.GREEN).build());
                                newPhases.add(TrafficLightPhase.builder()
                                    .greenRoadIds(p.getGreenRoadIds())
                                    .durationMs(slc.yellowDurationMs())
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
                        slc.intersectionId(), slc.greenDurationMs(), slc.yellowDurationMs());
                }
            }

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

    /**
     * Removes all vehicles from all lanes in the current road network.
     * Called on Stop to ensure a clean restart.
     */
    private void clearAllVehicles() {
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
