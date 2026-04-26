package com.trafficsimulator.engine.command;

import com.trafficsimulator.config.MapConfig;

public sealed interface SimulationCommand
        permits SimulationCommand.Start,
                SimulationCommand.Stop,
                SimulationCommand.Pause,
                SimulationCommand.Resume,
                SimulationCommand.SetSpawnRate,
                SimulationCommand.SetSpeedMultiplier,
                SimulationCommand.LoadMap,
                SimulationCommand.LoadConfig,
                SimulationCommand.SetMaxSpeed,
                SimulationCommand.AddObstacle,
                SimulationCommand.RemoveObstacle,
                SimulationCommand.CloseLane,
                SimulationCommand.SetLightCycle {

    /**
     * Starts the simulation. {@code seed} is the optional master RNG seed; when {@code null} the
     * engine falls back to {@code MapConfig.seed} (json) or {@code System.nanoTime()} (auto) per
     * D-01 precedence (command > json > auto). Logged at INFO via D-04.
     */
    record Start(Long seed) implements SimulationCommand {}

    record Stop() implements SimulationCommand {}

    record Pause() implements SimulationCommand {}

    record Resume() implements SimulationCommand {}

    record SetSpawnRate(double vehiclesPerSecond) implements SimulationCommand {}

    record SetSpeedMultiplier(double multiplier) implements SimulationCommand {}

    record LoadMap(String mapId) implements SimulationCommand {}

    record LoadConfig(MapConfig config) implements SimulationCommand {}

    record SetMaxSpeed(double maxSpeedMs) implements SimulationCommand {}

    record AddObstacle(String roadId, int laneIndex, double position)
            implements SimulationCommand {}

    record RemoveObstacle(String obstacleId) implements SimulationCommand {}

    record CloseLane(String roadId, int laneIndex) implements SimulationCommand {}

    record SetLightCycle(String intersectionId, long greenDurationMs, long yellowDurationMs)
            implements SimulationCommand {}
}
