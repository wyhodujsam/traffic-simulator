package com.trafficsimulator.engine.command;

public sealed interface SimulationCommand
    permits SimulationCommand.Start,
            SimulationCommand.Stop,
            SimulationCommand.Pause,
            SimulationCommand.Resume,
            SimulationCommand.SetSpawnRate,
            SimulationCommand.SetSpeedMultiplier,
            SimulationCommand.LoadMap {

    record Start()                                      implements SimulationCommand {}
    record Stop()                                       implements SimulationCommand {}
    record Pause()                                      implements SimulationCommand {}
    record Resume()                                     implements SimulationCommand {}
    record SetSpawnRate(double vehiclesPerSecond)        implements SimulationCommand {}
    record SetSpeedMultiplier(double multiplier)         implements SimulationCommand {}
    record LoadMap(String mapId)                         implements SimulationCommand {}
}
