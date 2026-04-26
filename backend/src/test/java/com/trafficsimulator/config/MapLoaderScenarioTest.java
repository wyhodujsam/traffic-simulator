package com.trafficsimulator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;

class MapLoaderScenarioTest {

    private MapLoader mapLoader;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MapValidator mapValidator = new MapValidator();
        mapLoader = new MapLoader(objectMapper, mapValidator);
    }

    @Test
    void loadsStraightRoad() throws IOException {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/straight-road.json");

        RoadNetwork network = loaded.network();
        assertThat(network.getRoads()).hasSize(1);
        assertThat(network.getRoads().get("r1").getLanes()).hasSize(3);
        assertThat(network.getSpawnPoints()).hasSize(3);
        assertThat(network.getDespawnPoints()).hasSize(3);
        assertThat(loaded.defaultSpawnRate()).isEqualTo(1.0);
    }

    @Test
    void loadsFourWaySignal() throws IOException {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/four-way-signal.json");

        RoadNetwork network = loaded.network();
        assertThat(network.getRoads()).hasSize(8);
        assertThat(network.getIntersections()).hasSize(1);
        assertThat(network.getSpawnPoints()).hasSize(4);

        Intersection center = network.getIntersections().get("n_center");
        assertThat(center).isNotNull();
        assertThat(center.getType()).isEqualTo(IntersectionType.SIGNAL);

        TrafficLight light = center.getTrafficLight();
        assertThat(light).isNotNull();
        assertThat(light.getPhases()).hasSize(6);
    }

    @Test
    void loadsPhantomJamCorridor() throws IOException {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/phantom-jam-corridor.json");

        RoadNetwork network = loaded.network();
        assertThat(network.getRoads()).hasSize(1);
        assertThat(network.getRoads().get("corridor").getLanes()).hasSize(2);
        assertThat(network.getSpawnPoints()).hasSize(2);
        assertThat(loaded.defaultSpawnRate()).isEqualTo(3.0);
    }

    @Test
    void loadsHighwayMerge() throws IOException {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/highway-merge.json");

        RoadNetwork network = loaded.network();
        assertThat(network.getRoads()).hasSize(3);
        assertThat(network.getIntersections()).hasSize(1);

        Intersection merge = network.getIntersections().get("merge_point");
        assertThat(merge).isNotNull();
        assertThat(merge.getType()).isEqualTo(IntersectionType.PRIORITY);
        assertThat(merge.getInboundRoadIds()).containsExactlyInAnyOrder("main_before", "ramp");
        assertThat(merge.getOutboundRoadIds()).containsExactly("main_after");
        assertThat(loaded.defaultSpawnRate()).isEqualTo(2.0);
    }

    @Test
    void loadsConstructionZone() throws IOException {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/construction-zone.json");

        RoadNetwork network = loaded.network();
        assertThat(network.getRoads()).hasSize(1);
        assertThat(network.getRoads().get("main_road").getLanes()).hasSize(3);
        assertThat(network.getSpawnPoints()).hasSize(1); // only lane 0 spawns
        assertThat(loaded.defaultSpawnRate()).isEqualTo(2.0);
        assertThat(network.getRoads().get("main_road").getSpeedLimit()).isEqualTo(22.2);
        // Lanes 1 and 2 should be pre-closed
        assertThat(network.getRoads().get("main_road").getLanes().get(0).isActive()).isTrue();
        assertThat(network.getRoads().get("main_road").getLanes().get(1).isActive()).isFalse();
        assertThat(network.getRoads().get("main_road").getLanes().get(2).isActive()).isFalse();
    }

    @Test
    void loadsCombinedLoop() throws IOException {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/combined-loop.json");

        RoadNetwork network = loaded.network();

        // 15 roads total
        assertThat(network.getRoads()).hasSize(15);

        // 8 intersections: 4 roundabout + 1 signal + 3 priority (merge + 2 loop bends)
        assertThat(network.getIntersections()).hasSize(8);

        // All 3 intersection types present
        assertThat(network.getIntersections().values())
                .extracting(i -> i.getType().name())
                .contains("ROUNDABOUT", "SIGNAL", "PRIORITY");

        // Signal intersection has traffic light with 6 phases
        Intersection signal = network.getIntersections().get("n_signal");
        assertThat(signal).isNotNull();
        assertThat(signal.getType()).isEqualTo(IntersectionType.SIGNAL);
        assertThat(signal.getTrafficLight()).isNotNull();
        assertThat(signal.getTrafficLight().getPhases()).hasSize(6);

        // Roundabout nodes are ROUNDABOUT type
        assertThat(network.getIntersections().get("n_ring_n").getType())
                .isEqualTo(IntersectionType.ROUNDABOUT);

        // Merge node is PRIORITY type
        assertThat(network.getIntersections().get("n_merge").getType())
                .isEqualTo(IntersectionType.PRIORITY);

        // Loop roads exist
        assertThat(network.getRoads())
                .containsKeys("r_sig_to_loop", "r_loop_bottom", "r_loop_to_rndbt");

        // 5 spawn points, 2 despawn points
        assertThat(network.getSpawnPoints()).hasSize(5);
        assertThat(network.getDespawnPoints()).hasSize(2);

        assertThat(loaded.defaultSpawnRate()).isEqualTo(0.8);
    }

    @Test
    void loadsRingRoad() throws IOException {
        // RING-01: ring-road.json (D-11 + D-12 + WAVE0 PRIORITY directive) loads cleanly with the
        // Plan 25-03 schema extensions populated end-to-end.
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/ring-road.json");
        RoadNetwork network = loaded.network();
        assertThat(network.getId()).isEqualTo("ring-road");
        assertThat(network.getRoads()).hasSize(8);
        assertThat(network.getIntersections()).hasSize(8);
        assertThat(network.getInitialVehicles()).hasSize(80);
        assertThat(network.getPerturbation()).isNotNull();
        assertThat(network.getPerturbation().getTick()).isEqualTo(200L);
        assertThat(network.getPerturbation().getDurationTicks()).isEqualTo(60L);
        assertThat(network.getPerturbation().getTargetSpeed()).isEqualTo(5.0);
        assertThat(network.getPerturbation().getVehicleIndex()).isZero();
        assertThat(network.getSeed()).isNull();
        // All 8 intersections are PRIORITY per WAVE0-SPIKE-RESULT.md directive
        assertThat(network.getIntersections().values())
                .allMatch(i -> i.getType() == IntersectionType.PRIORITY);
    }

    @Test
    void invalidMapThrowsWithDescriptiveError() {
        MapValidator validator = new MapValidator();
        MapConfig config = new MapConfig();
        // Missing nodes and roads
        config.setId("test");

        List<String> errors = validator.validate(config);
        assertThat(errors).isNotEmpty().anyMatch(e -> e.contains("At least one node is required"));
    }
}
