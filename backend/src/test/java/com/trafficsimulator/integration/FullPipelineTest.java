package com.trafficsimulator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.engine.VehicleSpawner;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FullPipelineTest {

    private RoadNetwork network;
    private VehicleSpawner spawner;

    @BeforeEach
    void setUp() throws Exception {
        MapLoader loader = new MapLoader(new ObjectMapper(), new MapValidator());
        network = loader.loadFromClasspath("maps/straight-road.json");
        spawner = new VehicleSpawner();
        spawner.setVehiclesPerSecond(1.0);
    }

    @Test
    void fullPipeline_loadMap_spawn_despawn() {
        // Verify map loaded
        assertThat(network.getRoads()).hasSize(1);
        Road road = network.getRoads().get("r1");
        assertThat(road.getLanes()).hasSize(3);

        // Simulate 1 second (20 ticks at 50ms) — should spawn 1 vehicle
        for (int tick = 1; tick <= 20; tick++) {
            spawner.tick(0.05, network, tick);
        }

        int totalVehicles = road.getLanes().stream()
            .mapToInt(lane -> lane.getVehicles().size())
            .sum();
        assertThat(totalVehicles).isEqualTo(1);

        // Move vehicle past road end to trigger despawn
        for (Lane lane : road.getLanes()) {
            lane.getVehicles().forEach(v -> v.setPosition(801.0));
        }

        spawner.despawnVehicles(network);

        int afterDespawn = road.getLanes().stream()
            .mapToInt(lane -> lane.getVehicles().size())
            .sum();
        assertThat(afterDespawn).isEqualTo(0);
    }

    @Test
    void fullPipeline_spawnedVehicle_hasValidIdmParams() throws Exception {
        spawner.tick(1.0, network, 1);

        // Find the spawned vehicle across all lanes
        var vehicle = network.getRoads().get("r1").getLanes().stream()
            .flatMap(lane -> lane.getVehicles().stream())
            .findFirst()
            .orElseThrow();

        assertThat(vehicle.getV0()).isBetween(33.3 * 0.8, 33.3 * 1.2);
        assertThat(vehicle.getAMax()).isBetween(1.4 * 0.8, 1.4 * 1.2);
        assertThat(vehicle.getB()).isBetween(2.0 * 0.8, 2.0 * 1.2);
        assertThat(vehicle.getS0()).isEqualTo(2.0);
        assertThat(vehicle.getT()).isBetween(1.5 * 0.8, 1.5 * 1.2);
    }
}
