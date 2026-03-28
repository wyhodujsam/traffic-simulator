package com.trafficsimulator.controller;

import com.trafficsimulator.dto.CommandDto;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.command.SimulationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CommandHandler {

    private static final Set<String> VALID_TYPES = Set.of(
        "START", "STOP", "PAUSE", "RESUME",
        "SET_SPAWN_RATE", "SET_SPEED_MULTIPLIER", "LOAD_MAP"
    );

    private final SimulationEngine simulationEngine;

    @MessageMapping("/command")
    public void handleCommand(@Payload CommandDto dto) {
        log.info("Received command: {}", dto.getType());
        SimulationCommand command = switch (dto.getType()) {
            case "START"                -> new SimulationCommand.Start();
            case "STOP"                 -> new SimulationCommand.Stop();
            case "PAUSE"                -> new SimulationCommand.Pause();
            case "RESUME"               -> new SimulationCommand.Resume();
            case "SET_SPAWN_RATE"       -> new SimulationCommand.SetSpawnRate(dto.getSpawnRate());
            case "SET_SPEED_MULTIPLIER" -> new SimulationCommand.SetSpeedMultiplier(dto.getMultiplier());
            case "LOAD_MAP"             -> new SimulationCommand.LoadMap(dto.getMapId());
            default -> throw new IllegalArgumentException(
                "Unknown command type: '" + dto.getType() + "'. " +
                "Valid types are: " + VALID_TYPES.stream().sorted().collect(Collectors.joining(", "))
            );
        };
        simulationEngine.enqueue(command);
    }
}
