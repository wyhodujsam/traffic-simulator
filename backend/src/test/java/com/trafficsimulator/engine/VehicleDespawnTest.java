package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleDespawnTest {

    private RoadNetwork buildNetwork(Road road) {
        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r1", road);
        return RoadNetwork.builder()
            .id("test").roads(roads)
            .intersections(new LinkedHashMap<>())
            .spawnPoints(List.of())
            .despawnPoints(List.of(new DespawnPoint("r1", 0, 800.0)))
            .build();
    }

    private Road buildRoad(Lane lane) {
        Road road = Road.builder()
            .id("r1").name("Test").length(800.0).speedLimit(33.3)
            .startX(0).startY(0).endX(800).endY(0)
            .fromNodeId("n1").toNodeId("n2")
            .lanes(new ArrayList<>())
            .build();
        road.getLanes().add(lane);
        return road;
    }

    @Test
    void despawn_vehicleAtEndOfLane_isRemoved() {
        Lane lane = Lane.builder()
            .id("r1-lane0").laneIndex(0)
            .length(800.0).maxSpeed(33.3).active(true)
            .build();
        Road road = buildRoad(lane);

        Vehicle v = Vehicle.builder()
            .id("v1").position(800.5).speed(10.0).lane(lane).length(4.5)
            .v0(33.3).aMax(1.4).b(2.0).s0(2.0).timeHeadway(1.5).spawnedAt(0)
            .build();
        lane.addVehicle(v);

        RoadNetwork network = buildNetwork(road);

        new VehicleSpawner().despawnVehicles(network);

        assertThat(lane.getVehiclesView()).isEmpty();
    }

    @Test
    void despawn_vehicleBeforeEndOfLane_isKept() {
        Lane lane = Lane.builder()
            .id("r1-lane0").laneIndex(0)
            .length(800.0).maxSpeed(33.3).active(true)
            .build();
        Road road = buildRoad(lane);

        Vehicle v = Vehicle.builder()
            .id("v1").position(799.9).speed(10.0).lane(lane).length(4.5)
            .v0(33.3).aMax(1.4).b(2.0).s0(2.0).timeHeadway(1.5).spawnedAt(0)
            .build();
        lane.addVehicle(v);

        RoadNetwork network = buildNetwork(road);

        new VehicleSpawner().despawnVehicles(network);

        assertThat(lane.getVehiclesView()).hasSize(1);
    }

    @Test
    void despawn_exactlyAtLaneLength_isRemoved() {
        Lane lane = Lane.builder()
            .id("r1-lane0").laneIndex(0)
            .length(800.0).maxSpeed(33.3).active(true)
            .build();
        Road road = buildRoad(lane);

        Vehicle v = Vehicle.builder()
            .id("v1").position(800.0).speed(0.0).lane(lane).length(4.5)
            .v0(33.3).aMax(1.4).b(2.0).s0(2.0).timeHeadway(1.5).spawnedAt(0)
            .build();
        lane.addVehicle(v);

        RoadNetwork network = buildNetwork(road);

        new VehicleSpawner().despawnVehicles(network);

        assertThat(lane.getVehiclesView()).isEmpty();
    }
}
