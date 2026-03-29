package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Data
@Builder
public class RoadNetwork {
    private String id;                                // map scenario id

    @Builder.Default
    private Map<String, Road> roads = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Intersection> intersections = new LinkedHashMap<>();

    private List<SpawnPoint> spawnPoints;
    private List<DespawnPoint> despawnPoints;

    /** Flat stream of all lanes across all roads. */
    public Stream<Lane> getAllLanes() {
        return roads.values().stream()
            .flatMap(road -> road.getLanes().stream());
    }

    /** Flat stream of all vehicles across all lanes. */
    public Stream<Vehicle> getAllVehicles() {
        return getAllLanes()
            .flatMap(lane -> lane.getVehiclesView().stream());
    }

    /** Find road by ID. */
    public Optional<Road> findRoad(String id) {
        return Optional.ofNullable(roads.get(id));
    }

    /** Find lane by global lane ID (e.g. "r1-lane0"). */
    public Optional<Lane> findLane(String laneId) {
        return getAllLanes()
            .filter(lane -> lane.getId().equals(laneId))
            .findFirst();
    }
}
