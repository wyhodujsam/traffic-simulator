package com.trafficsimulator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.RoadNetwork;

class MapLoaderLoadFromConfigTest {

    private MapLoader mapLoader;

    @BeforeEach
    void setUp() {
        mapLoader = new MapLoader(new ObjectMapper(), new MapValidator());
    }

    private MapConfig buildValidConfig() {
        MapConfig config = new MapConfig();
        config.setId("test-map");
        config.setName("Test Map");
        config.setDefaultSpawnRate(2.5);

        MapConfig.NodeConfig from = new MapConfig.NodeConfig();
        from.setId("n1");
        from.setType("ENTRY");
        from.setX(50.0);
        from.setY(300.0);

        MapConfig.NodeConfig to = new MapConfig.NodeConfig();
        to.setId("n2");
        to.setType("EXIT");
        to.setX(850.0);
        to.setY(300.0);

        config.setNodes(List.of(from, to));

        MapConfig.RoadConfig road = new MapConfig.RoadConfig();
        road.setId("r1");
        road.setName("Main Road");
        road.setFromNodeId("n1");
        road.setToNodeId("n2");
        road.setLength(800.0);
        road.setSpeedLimit(33.3);
        road.setLaneCount(2);

        config.setRoads(List.of(road));

        MapConfig.SpawnPointConfig spawn = new MapConfig.SpawnPointConfig();
        spawn.setRoadId("r1");
        spawn.setLaneIndex(0);
        spawn.setPosition(0.0);
        config.setSpawnPoints(List.of(spawn));

        MapConfig.DespawnPointConfig despawn = new MapConfig.DespawnPointConfig();
        despawn.setRoadId("r1");
        despawn.setLaneIndex(0);
        despawn.setPosition(800.0);
        config.setDespawnPoints(List.of(despawn));

        return config;
    }

    @Test
    void loadFromConfig_validConfig_returnsLoadedMapWithRoadNetwork() {
        MapConfig config = buildValidConfig();

        MapLoader.LoadedMap result = mapLoader.loadFromConfig(config);

        assertThat(result).isNotNull();
        assertThat(result.network()).isNotNull();
        assertThat(result.network().getRoads()).hasSize(1);
    }

    @Test
    void loadFromConfig_invalidConfig_emptyRoads_throwsIllegalArgumentException() {
        MapConfig config = new MapConfig();
        config.setId("bad-map");
        config.setNodes(List.of());
        config.setRoads(List.of());

        assertThatThrownBy(() -> mapLoader.loadFromConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validation failed");
    }

    @Test
    void loadFromConfig_preservesDefaultSpawnRate() {
        MapConfig config = buildValidConfig();
        config.setDefaultSpawnRate(3.7);

        MapLoader.LoadedMap result = mapLoader.loadFromConfig(config);

        assertThat(result.defaultSpawnRate()).isEqualTo(3.7);
    }

    @Test
    void loadConfig_recordCanBeInstantiated() {
        MapConfig config = buildValidConfig();
        SimulationCommand.LoadConfig cmd = new SimulationCommand.LoadConfig(config);

        assertThat(cmd).isNotNull();
        assertThat(cmd.config()).isSameAs(config);
        assertThat(cmd).isInstanceOf(SimulationCommand.class);
    }

    @Test
    void loadFromConfig_roadNetworkHasCorrectId() {
        MapConfig config = buildValidConfig();

        RoadNetwork network = mapLoader.loadFromConfig(config).network();

        assertThat(network.getId()).isEqualTo("test-map");
    }

    @Test
    void loadFromConfig_roadNetworkHasSpawnAndDespawnPoints() {
        MapConfig config = buildValidConfig();

        RoadNetwork network = mapLoader.loadFromConfig(config).network();

        assertThat(network.getSpawnPoints()).hasSize(1);
        assertThat(network.getDespawnPoints()).hasSize(1);
    }
}
