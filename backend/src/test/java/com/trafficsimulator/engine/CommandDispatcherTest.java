package com.trafficsimulator.engine;

import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDispatcherTest {

    private SimulationEngine engine;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        engine = new SimulationEngine(null, null);
        dispatcher = new CommandDispatcher(engine, null, null, null);
        engine.setCommandDispatcher(dispatcher);
    }

    @Test
    void testStartCommand() {
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);

        dispatcher.dispatch(new SimulationCommand.Start());

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void testStopCommand() {
        // Start first so stop is accepted
        dispatcher.dispatch(new SimulationCommand.Start());
        engine.getTickCounter().set(100);

        dispatcher.dispatch(new SimulationCommand.Stop());

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);
        assertThat(engine.getTickCounter().get()).isZero();
    }

    @Test
    void testPauseResumeRoundTrip() {
        dispatcher.dispatch(new SimulationCommand.Start());
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);

        dispatcher.dispatch(new SimulationCommand.Pause());
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.PAUSED);

        dispatcher.dispatch(new SimulationCommand.Resume());
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void testSetSpawnRate() {
        dispatcher.dispatch(new SimulationCommand.SetSpawnRate(2.5));

        assertThat(engine.getSpawnRate()).isEqualTo(2.5);
    }

    @Test
    void testSetSpeedMultiplier() {
        dispatcher.dispatch(new SimulationCommand.SetSpeedMultiplier(3.0));

        assertThat(engine.getSpeedMultiplier()).isEqualTo(3.0);
    }

    @Test
    void testCloseLane() {
        // Set up road with 3 lanes
        Road road = createRoadWithLanes("road1", 3);
        RoadNetwork network = createNetwork(Map.of("road1", road));
        engine.setRoadNetwork(network);

        dispatcher.dispatch(new SimulationCommand.CloseLane("road1", 1));

        assertThat(road.getLanes().get(0).isActive()).isTrue();
        assertThat(road.getLanes().get(1).isActive()).isFalse();
        assertThat(road.getLanes().get(2).isActive()).isTrue();
    }

    @Test
    void testCloseLaneLastLaneRejected() {
        // Single-lane road — CloseLane should be a no-op
        Road road = createRoadWithLanes("road1", 1);
        RoadNetwork network = createNetwork(Map.of("road1", road));
        engine.setRoadNetwork(network);

        dispatcher.dispatch(new SimulationCommand.CloseLane("road1", 0));

        assertThat(road.getLanes().get(0).isActive()).isTrue();
    }

    @Test
    void testLoadMap() throws NoSuchFieldException, IllegalAccessException {
        // Wire in a real MapLoader so LoadMap can actually load
        ObjectMapper objectMapper = new ObjectMapper();
        MapValidator mapValidator = new MapValidator();
        MapLoader mapLoader = new MapLoader(objectMapper, mapValidator);

        var mapLoaderField = CommandDispatcher.class.getDeclaredField("mapLoader");
        mapLoaderField.setAccessible(true);
        mapLoaderField.set(dispatcher, mapLoader);

        // Start simulation first so we can verify it stops on LoadMap
        dispatcher.dispatch(new SimulationCommand.Start());
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);

        dispatcher.dispatch(new SimulationCommand.LoadMap("straight-road"));

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);
        assertThat(engine.getRoadNetwork()).isNotNull();
        assertThat(engine.getRoadNetwork().getId()).isEqualTo("straight-road");
    }

    // --- helpers ---

    private Road createRoadWithLanes(String roadId, int laneCount) {
        List<Lane> lanes = new ArrayList<>();
        Road road = Road.builder()
            .id(roadId)
            .name(roadId)
            .length(500.0)
            .speedLimit(33.33)
            .fromNodeId("n1")
            .toNodeId("n2")
            .startX(0).startY(0)
            .endX(500).endY(0)
            .lanes(lanes)
            .build();
        for (int i = 0; i < laneCount; i++) {
            lanes.add(Lane.builder()
                .id(roadId + "-lane" + i)
                .laneIndex(i)
                .road(road)
                .length(500.0)
                .maxSpeed(33.33)
                .active(true)
                .build());
        }
        return road;
    }

    private RoadNetwork createNetwork(Map<String, Road> roads) {
        return RoadNetwork.builder()
            .id("test-network")
            .roads(new LinkedHashMap<>(roads))
            .intersections(new LinkedHashMap<>())
            .spawnPoints(List.of())
            .despawnPoints(List.of())
            .build();
    }
}
