package com.trafficsimulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapLoaderTest {

    private MapLoader mapLoader;

    @BeforeEach
    void setUp() {
        mapLoader = new MapLoader(new ObjectMapper(), new MapValidator());
    }

    @Test
    void loadStraightRoad_returnsValidRoadNetwork() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");

        assertThat(network).isNotNull();
        assertThat(network.getId()).isEqualTo("straight-road");
    }

    @Test
    void loadStraightRoad_hasOneRoadWithThreeLanes() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");

        assertThat(network.getRoads()).hasSize(1);
        Road road = network.getRoads().get("r1");
        assertThat(road).isNotNull();
        assertThat(road.getLanes()).hasSize(3);
        assertThat(road.getLength()).isEqualTo(800.0);
        assertThat(road.getSpeedLimit()).isEqualTo(33.3);
    }

    @Test
    void loadStraightRoad_laneIdsAreCorrect() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");
        Road road = network.getRoads().get("r1");

        assertThat(road.getLanes().get(0).getId()).isEqualTo("r1-lane0");
        assertThat(road.getLanes().get(1).getId()).isEqualTo("r1-lane1");
        assertThat(road.getLanes().get(2).getId()).isEqualTo("r1-lane2");
    }

    @Test
    void loadStraightRoad_laneBackReferencesRoad() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");
        Road road = network.getRoads().get("r1");
        Lane lane = road.getLanes().get(0);

        assertThat(lane.getRoad()).isSameAs(road);
        assertThat(lane.getLength()).isEqualTo(800.0);
        assertThat(lane.isActive()).isTrue();
    }

    @Test
    void loadStraightRoad_nodeCoordinatesPropagated() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");
        Road road = network.getRoads().get("r1");

        assertThat(road.getStartX()).isEqualTo(50.0);
        assertThat(road.getStartY()).isEqualTo(300.0);
        assertThat(road.getEndX()).isEqualTo(850.0);
        assertThat(road.getEndY()).isEqualTo(300.0);
    }

    @Test
    void loadStraightRoad_hasSpawnAndDespawnPoints() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");

        assertThat(network.getSpawnPoints()).hasSize(3);
        assertThat(network.getDespawnPoints()).hasSize(3);
        assertThat(network.getSpawnPoints().get(0).roadId()).isEqualTo("r1");
        assertThat(network.getDespawnPoints().get(0).position()).isEqualTo(800.0);
    }

    @Test
    void loadStraightRoad_intersectionsEmpty() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json");

        assertThat(network.getIntersections()).isEmpty();
    }

    @Test
    void loadNonexistentFile_throwsException() {
        assertThatThrownBy(() -> mapLoader.loadFromClasspath("maps/nonexistent.json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }
}
