package com.trafficsimulator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.engine.SimulationStatus;

/**
 * DET-06: {@code RUN_FOR_TICKS_FAST=N} auto-stops at the target tick. The plan's source text
 * specifies {@code RUN_FOR_TICKS=200}; we use the FAST variant for CI latency reasons (the
 * dispatcher routes both through the same auto-stop machinery so the assertion remains valid).
 *
 * <p>File naming: {@code *Test.java} so Surefire picks it up — see Phase25IntegrationBase javadoc.
 */
class RunForTicksTest extends Phase25IntegrationBase {

    @Test
    void autoStopAtTargetTick() throws Exception {
        loadScenario("ring-road");
        startAndRunFast(42L, 200L);

        // Wait for the engine status to flip to STOPPED (TickEmitter emits the auto-stop on the
        // tick AFTER the target is reached).
        waitForStopped(15_000L);

        long finalTick = engine.getTickCounter().get();
        assertThat(engine.getStatus())
                .as("DET-06: engine must auto-STOP after RUN_FOR_TICKS")
                .isEqualTo(SimulationStatus.STOPPED);
        // Tolerance: target ± a few ticks for last-tick race (auto-stop check happens AFTER
        // increment, so the counter sits at exactly target or one past it).
        assertThat(finalTick)
                .as("DET-06: tick counter at or near 200 after auto-stop")
                .isBetween(200L, 210L);
        assertThat(engine.isFastMode())
                .as("fast-mode flag must clear when RUN_FOR_TICKS_FAST completes")
                .isFalse();
    }
}
