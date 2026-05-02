package com.trafficsimulator.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.trafficsimulator.config.MapConfig;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoadNetwork {
    private String id; // map scenario id

    /**
     * Optional master RNG seed loaded from {@code MapConfig.seed} (Plan 03 wires this from JSON via
     * {@link com.trafficsimulator.config.MapLoader}). Per D-01 precedence (command > json > auto),
     * {@link com.trafficsimulator.engine.SimulationEngine#resolveSeedAndStart(Long)} reads this
     * when no STOMP {@code Start.seed} is supplied.
     */
    private Long seed;

    /**
     * Optional perturbation block (D-12) — populated by {@link
     * com.trafficsimulator.config.MapLoader} from {@link MapConfig#getPerturbation()}. Read by
     * {@link com.trafficsimulator.engine.PerturbationManager#getActiveV0} on each physics tick.
     */
    private MapConfig.PerturbationConfig perturbation;

    /**
     * Optional list of vehicles to prime at load time (CONTEXT.md §Q2). Populated by {@link
     * com.trafficsimulator.config.MapLoader} from {@link MapConfig#getInitialVehicles()}; consumed
     * by {@link com.trafficsimulator.engine.CommandDispatcher} after {@code
     * handleLoadMap}/{@code handleLoadConfig}.
     */
    private List<MapConfig.InitialVehicleConfig> initialVehicles;

    @Builder.Default private Map<String, Road> roads = new LinkedHashMap<>();

    @Builder.Default private Map<String, Intersection> intersections = new LinkedHashMap<>();

    private List<SpawnPoint> spawnPoints;
    private List<DespawnPoint> despawnPoints;

    /** Flat stream of all lanes across all roads. */
    public Stream<Lane> getAllLanes() {
        return roads.values().stream().flatMap(road -> road.getLanes().stream());
    }

    /** Flat stream of all vehicles across all lanes. */
    public Stream<Vehicle> getAllVehicles() {
        return getAllLanes().flatMap(lane -> lane.getVehiclesView().stream());
    }

    /** Find road by ID. */
    public Optional<Road> findRoad(String id) {
        return Optional.ofNullable(roads.get(id));
    }

    /** Find lane by global lane ID (e.g. "r1-lane0"). */
    public Optional<Lane> findLane(String laneId) {
        return getAllLanes().filter(lane -> lane.getId().equals(laneId)).findFirst();
    }
}
