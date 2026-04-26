package com.trafficsimulator.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoadNetwork {
    private String id; // map scenario id

    /**
     * Optional master RNG seed loaded from {@code MapConfig.seed} (Plan 03 will populate this from
     * JSON). Per D-01 precedence (command > json > auto), {@link
     * com.trafficsimulator.engine.SimulationEngine#resolveSeedAndStart(Long)} reads this when no
     * STOMP {@code Start.seed} is supplied. Placeholder field added in Plan 02 — Plan 03 wires the
     * loader.
     */
    private Long seed;

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
