package com.trafficsimulator.engine.command;

public sealed interface SimulationCommand
        permits SimulationCommand.Start,
                SimulationCommand.Stop,
                SimulationCommand.Pause,
                SimulationCommand.Resume,
                SimulationCommand.SetSpawnRate,
                SimulationCommand.SetSpeedMultiplier,
                SimulationCommand.LoadMap,
                SimulationCommand.SetMaxSpeed,
                SimulationCommand.AddObstacle,
                SimulationCommand.RemoveObstacle,
                SimulationCommand.CloseLane,
                SimulationCommand.SetLightCycle {

    record Start() implements SimulationCommand {}

    record Stop() implements SimulationCommand {}

    record Pause() implements SimulationCommand {}

    record Resume() implements SimulationCommand {}

    record SetSpawnRate(double vehiclesPerSecond) implements SimulationCommand {}

    record SetSpeedMultiplier(double multiplier) implements SimulationCommand {}

    record LoadMap(String mapId) implements SimulationCommand {}

    record SetMaxSpeed(double maxSpeedMs) implements SimulationCommand {}

    record AddObstacle(String roadId, int laneIndex, double position)
            implements SimulationCommand {}

    record RemoveObstacle(String obstacleId) implements SimulationCommand {}

    record CloseLane(String roadId, int laneIndex) implements SimulationCommand {}

    record SetLightCycle(String intersectionId, long greenDurationMs, long yellowDurationMs)
            implements SimulationCommand {}
}
