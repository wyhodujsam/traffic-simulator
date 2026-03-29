package com.trafficsimulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MapLoaderIntersectionTest {

    private MapLoader mapLoader;

    @BeforeEach
    void setUp() {
        mapLoader = new MapLoader(new ObjectMapper(), new MapValidator());
    }

    @Test
    void loadsFourWaySignalMap() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/four-way-signal.json").network();

        assertThat(network).isNotNull();
        assertThat(network.getId()).isEqualTo("four-way-signal");

        // 8 roads (4 inbound + 4 outbound)
        assertThat(network.getRoads()).hasSize(8);

        // 1 intersection with id "n_center"
        assertThat(network.getIntersections()).hasSize(1);
        Intersection ixtn = network.getIntersections().get("n_center");
        assertThat(ixtn).isNotNull();
        assertThat(ixtn.getType()).isEqualTo(IntersectionType.SIGNAL);

        // 4 inbound, 4 outbound roads
        assertThat(ixtn.getInboundRoadIds()).hasSize(4);
        assertThat(ixtn.getOutboundRoadIds()).hasSize(4);

        // TrafficLight with 6 phases
        assertThat(ixtn.getTrafficLight()).isNotNull();
        assertThat(ixtn.getTrafficLight().getPhases()).hasSize(6);

        // 4 spawn points, 4 despawn points
        assertThat(network.getSpawnPoints()).hasSize(4);
        assertThat(network.getDespawnPoints()).hasSize(4);
    }

    @Test
    void trafficLightPhasesMatchConfig() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/four-way-signal.json").network();
        Intersection ixtn = network.getIntersections().get("n_center");
        TrafficLight light = ixtn.getTrafficLight();

        // Phase 0: GREEN, north and south
        TrafficLightPhase phase0 = light.getPhases().get(0);
        assertThat(phase0.getType()).isEqualTo(TrafficLightPhase.PhaseType.GREEN);
        assertThat(phase0.getGreenRoadIds()).contains("r_north_in", "r_south_in");
        assertThat(phase0.getDurationMs()).isEqualTo(30000);

        // Phase 1: YELLOW, north and south
        TrafficLightPhase phase1 = light.getPhases().get(1);
        assertThat(phase1.getType()).isEqualTo(TrafficLightPhase.PhaseType.YELLOW);
        assertThat(phase1.getGreenRoadIds()).contains("r_north_in", "r_south_in");

        // Phase 2: ALL_RED, empty greenRoadIds
        TrafficLightPhase phase2 = light.getPhases().get(2);
        assertThat(phase2.getType()).isEqualTo(TrafficLightPhase.PhaseType.ALL_RED);
        assertThat(phase2.getGreenRoadIds()).isEmpty();

        // Phase 3: GREEN, west and east
        TrafficLightPhase phase3 = light.getPhases().get(3);
        assertThat(phase3.getType()).isEqualTo(TrafficLightPhase.PhaseType.GREEN);
        assertThat(phase3.getGreenRoadIds()).contains("r_west_in", "r_east_in");
    }

    @Test
    void straightRoadStillLoadsCorrectly() throws Exception {
        RoadNetwork network = mapLoader.loadFromClasspath("maps/straight-road.json").network();

        assertThat(network.getRoads()).hasSize(1);
        assertThat(network.getIntersections()).isEmpty();
        assertThat(network.getSpawnPoints()).hasSize(3);
    }
}
