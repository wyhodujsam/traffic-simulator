package com.trafficsimulator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.trafficsimulator.dto.CommandDto;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.command.SimulationCommand;

/**
 * Phase 25 Plan 05 — T-25-02 DoS bound validation. {@link CommandHandler} MUST reject {@code
 * RUN_FOR_TICKS} / {@code RUN_FOR_TICKS_FAST} payloads where {@code ticks} falls outside the
 * inclusive range {@code [1, 1_000_000]}, before the command reaches the engine queue.
 */
class CommandHandlerValidationTest {

    private SimulationEngine engine;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        engine = mock(SimulationEngine.class);
        handler = new CommandHandler(engine);
    }

    @Test
    void runForTicks_validValue_enqueues() {
        CommandDto dto = makeDto("RUN_FOR_TICKS", 100L);
        handler.handleCommand(dto);
        ArgumentCaptor<SimulationCommand> captor = ArgumentCaptor.forClass(SimulationCommand.class);
        verify(engine).enqueue(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SimulationCommand.RunForTicks.class);
        assertThat(((SimulationCommand.RunForTicks) captor.getValue()).ticks()).isEqualTo(100L);
    }

    @Test
    void runForTicks_oneTick_accepted() {
        CommandDto dto = makeDto("RUN_FOR_TICKS", 1L);
        handler.handleCommand(dto);
        verify(engine).enqueue(new SimulationCommand.RunForTicks(1L));
    }

    @Test
    void runForTicks_oneMillion_accepted() {
        CommandDto dto = makeDto("RUN_FOR_TICKS", 1_000_000L);
        handler.handleCommand(dto);
        verify(engine).enqueue(new SimulationCommand.RunForTicks(1_000_000L));
    }

    @Test
    void runForTicks_zero_throws() {
        CommandDto dto = makeDto("RUN_FOR_TICKS", 0L);
        assertThatThrownBy(() -> handler.handleCommand(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in 1..1_000_000");
    }

    @Test
    void runForTicks_negative_throws() {
        CommandDto dto = makeDto("RUN_FOR_TICKS", -1L);
        assertThatThrownBy(() -> handler.handleCommand(dto))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runForTicks_overMillion_throws() {
        CommandDto dto = makeDto("RUN_FOR_TICKS", 1_000_001L);
        assertThatThrownBy(() -> handler.handleCommand(dto))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runForTicksFast_validatesIdentically() {
        CommandDto dto = makeDto("RUN_FOR_TICKS_FAST", 0L);
        assertThatThrownBy(() -> handler.handleCommand(dto))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runForTicksFast_validValue_enqueues() {
        CommandDto dto = makeDto("RUN_FOR_TICKS_FAST", 500L);
        handler.handleCommand(dto);
        ArgumentCaptor<SimulationCommand> captor = ArgumentCaptor.forClass(SimulationCommand.class);
        verify(engine).enqueue(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SimulationCommand.RunForTicksFast.class);
        assertThat(((SimulationCommand.RunForTicksFast) captor.getValue()).ticks()).isEqualTo(500L);
    }

    @Test
    void runForTicks_nullTicks_throws() {
        CommandDto dto = new CommandDto();
        dto.setType("RUN_FOR_TICKS");
        dto.setTicks(null);
        assertThatThrownBy(() -> handler.handleCommand(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires 'ticks' field");
    }

    private CommandDto makeDto(String type, long ticks) {
        CommandDto dto = new CommandDto();
        dto.setType(type);
        dto.setTicks(ticks);
        return dto;
    }
}
