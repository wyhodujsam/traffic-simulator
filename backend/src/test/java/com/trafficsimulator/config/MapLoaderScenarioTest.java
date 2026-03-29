package com.trafficsimulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(network.getSpawnPoints()).hasSize(3);
        assertThat(loaded.defaultSpawnRate()).isEqualTo(2.5);
        assertThat(network.getRoads().get("main_road").getSpeedLimit()).isEqualTo(27.8);
    }

    @Test
    void invalidMapThrowsWithDescriptiveError() {
        MapValidator validator = new MapValidator();
        MapConfig config = new MapConfig();
        // Missing nodes and roads
        config.setId("test");

        List<String> errors = validator.validate(config);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.contains("At least one node is required"));
    }
}
