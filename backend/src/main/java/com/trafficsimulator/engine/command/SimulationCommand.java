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
                SimulationCommand.SetLightCycle,
                SimulationCommand.RunForTicks,
                SimulationCommand.RunForTicksFast {

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

    /**
     * Phase 25 D-13: run {@code ticks} ticks at wall-clock 20 Hz (respects {@code
     * speedMultiplier}), then auto-stop. CommandHandler validates {@code ticks > 0 && ticks <=
     * 1_000_000} before enqueue (T-25-02 DoS mitigation).
     */
    record RunForTicks(long ticks) implements SimulationCommand {}

    /**
     * Phase 25 D-13: run {@code ticks} ticks as fast as the JVM permits (no STOMP frames during
     * run, only terminal snapshot). Bypasses {@code @Scheduled} via {@code FastSimulationRunner};
     * {@code TickEmitter} early-returns when {@code engine.isFastMode()} (Pitfall #5 race
     * mitigation).
     */
    record RunForTicksFast(long ticks) implements SimulationCommand {}
}
