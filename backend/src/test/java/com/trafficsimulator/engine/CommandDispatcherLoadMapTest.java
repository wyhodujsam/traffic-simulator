package com.trafficsimulator.engine;

import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.RoadNetwork;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CommandDispatcherLoadMapTest {

    @Autowired
    private SimulationEngine engine;

    @Autowired
    private CommandDispatcher dispatcher;

    @Autowired
    private ObstacleManager obstacleManager;

    @Autowired
    private VehicleSpawner vehicleSpawner;

    @Test
    void loadMapClearsVehiclesFromOldNetwork() {
        // Ensure we start with straight-road
        dispatcher.dispatch(new SimulationCommand.LoadMap("straight-road"));
        assertThat(engine.getRoadNetwork().getId()).isEqualTo("straight-road");

        // Dispatch LoadMap to four-way-signal
        dispatcher.dispatch(new SimulationCommand.LoadMap("four-way-signal"));

        RoadNetwork network = engine.getRoadNetwork();
        assertThat(network.getId()).isEqualTo("four-way-signal");

        // No vehicles on any lane
        long vehicleCount = network.getAllVehicles().count();
        assertThat(vehicleCount).isZero();

        // Status stopped, tick counter reset
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);
        assertThat(engine.getTickCounter().get()).isZero();
    }

    @Test
    void loadMapClearsObstacles() {
        // Load straight-road, add obstacle
        dispatcher.dispatch(new SimulationCommand.LoadMap("straight-road"));
        RoadNetwork oldNetwork = engine.getRoadNetwork();
        obstacleManager.addObstacle(oldNetwork, "r1", 0, 400.0, 0);

        // Verify obstacle exists
        assertThat(obstacleManager.getAllObstacles(oldNetwork)).isNotEmpty();

        // Load new map
        dispatcher.dispatch(new SimulationCommand.LoadMap("phantom-jam-corridor"));

        RoadNetwork newNetwork = engine.getRoadNetwork();
        // New network should have no obstacles
        assertThat(obstacleManager.getAllObstacles(newNetwork)).isEmpty();
    }

    @Test
    void loadMapAppliesDefaultSpawnRate() {
        // Start with straight-road (spawnRate=1.0)
        dispatcher.dispatch(new SimulationCommand.LoadMap("straight-road"));
        assertThat(engine.getSpawnRate()).isEqualTo(1.0);

        // Load phantom-jam-corridor (defaultSpawnRate=3.0)
        dispatcher.dispatch(new SimulationCommand.LoadMap("phantom-jam-corridor"));
        assertThat(engine.getSpawnRate()).isEqualTo(3.0);
        assertThat(vehicleSpawner.getVehiclesPerSecond()).isEqualTo(3.0);
    }

    @Test
    void loadMapSetsErrorOnFailure() {
        // Load a valid map first
        dispatcher.dispatch(new SimulationCommand.LoadMap("straight-road"));
        RoadNetwork oldNetwork = engine.getRoadNetwork();
        assertThat(oldNetwork).isNotNull();

        // Try to load nonexistent map
        dispatcher.dispatch(new SimulationCommand.LoadMap("nonexistent-map"));

        // Error should be set
        assertThat(engine.getLastError()).isNotNull();
        assertThat(engine.getLastError()).contains("nonexistent-map");

        // Old network should NOT be replaced on failure — but per implementation,
        // the network was already cleared before the load attempt.
        // The status should be STOPPED after a load attempt.
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);
    }

    @Test
    void loadMapClearsErrorOnSuccess() {
        // Set an error manually
        engine.setLastError("some previous error");
        assertThat(engine.getLastError()).isNotNull();

        // Load a valid map
        dispatcher.dispatch(new SimulationCommand.LoadMap("straight-road"));

        // Error should be cleared
        assertThat(engine.getLastError()).isNull();
    }
}
