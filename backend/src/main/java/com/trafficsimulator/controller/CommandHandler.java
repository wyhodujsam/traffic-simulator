package com.trafficsimulator.controller;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.trafficsimulator.dto.CommandDto;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.command.SimulationCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CommandHandler {

    private static final Set<String> VALID_TYPES =
            Set.of(
                    "START",
                    "STOP",
                    "PAUSE",
                    "RESUME",
                    "SET_SPAWN_RATE",
                    "SET_SPEED_MULTIPLIER",
                    "LOAD_MAP",
                    "SET_MAX_SPEED",
                    "ADD_OBSTACLE",
                    "REMOVE_OBSTACLE",
                    "CLOSE_LANE",
                    "SET_LIGHT_CYCLE");

    private final SimulationEngine simulationEngine;

    @MessageMapping("/command")
    public void handleCommand(@Payload CommandDto dto) {
        log.info("Received command: {}", dto.getType());
        SimulationCommand command =
                switch (dto.getType()) {
                    case "START" -> new SimulationCommand.Start(dto.getSeed());
                    case "STOP" -> new SimulationCommand.Stop();
                    case "PAUSE" -> new SimulationCommand.Pause();
                    case "RESUME" -> new SimulationCommand.Resume();
                    case "SET_SPAWN_RATE" -> new SimulationCommand.SetSpawnRate(dto.getSpawnRate());
                    case "SET_SPEED_MULTIPLIER" ->
                            new SimulationCommand.SetSpeedMultiplier(dto.getMultiplier());
                    case "LOAD_MAP" -> new SimulationCommand.LoadMap(dto.getMapId());
                    case "SET_MAX_SPEED" -> new SimulationCommand.SetMaxSpeed(dto.getMaxSpeed());
                    case "ADD_OBSTACLE" ->
                            new SimulationCommand.AddObstacle(
                                    dto.getRoadId(), dto.getLaneIndex(), dto.getPosition());
                    case "REMOVE_OBSTACLE" ->
                            new SimulationCommand.RemoveObstacle(dto.getObstacleId());
                    case "CLOSE_LANE" ->
                            new SimulationCommand.CloseLane(dto.getRoadId(), dto.getLaneIndex());
                    case "SET_LIGHT_CYCLE" ->
                            new SimulationCommand.SetLightCycle(
                                    dto.getIntersectionId(),
                                    dto.getGreenDurationMs() != null
                                            ? dto.getGreenDurationMs()
                                            : 30_000,
                                    dto.getYellowDurationMs() != null
                                            ? dto.getYellowDurationMs()
                                            : 3000);
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown command type: '"
                                            + dto.getType()
                                            + "'. "
                                            + "Valid types are: "
                                            + VALID_TYPES.stream()
                                                    .sorted()
                                                    .collect(Collectors.joining(", ")));
                };
        simulationEngine.enqueue(command);
    }
}
