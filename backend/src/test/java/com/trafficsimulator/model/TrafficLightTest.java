package com.trafficsimulator.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficLightTest {

    private TrafficLight buildTwoPhaseLight() {
        List<TrafficLightPhase> phases = new ArrayList<>();
        phases.add(TrafficLightPhase.builder()
            .greenRoadIds(Set.of("r1", "r3"))
            .durationMs(30000)
            .type(TrafficLightPhase.PhaseType.GREEN)
            .build());
        phases.add(TrafficLightPhase.builder()
            .greenRoadIds(Set.of("r1", "r3"))
            .durationMs(3000)
            .type(TrafficLightPhase.PhaseType.YELLOW)
            .build());
        return TrafficLight.builder()
            .intersectionId("ix1")
            .phases(phases)
            .currentPhaseIndex(0)
            .phaseElapsedMs(0)
            .build();
    }

    @Test
    void phaseCyclesCorrectly() {
        TrafficLight light = buildTwoPhaseLight();

        // Tick for 31 seconds — should exceed 30s GREEN phase and enter YELLOW (index 1)
        light.tick(31.0);

        assertThat(light.getCurrentPhaseIndex()).isEqualTo(1);
        assertThat(light.getCurrentPhase().getType()).isEqualTo(TrafficLightPhase.PhaseType.YELLOW);
    }

    @Test
    void phaseWrapsAround() {
        TrafficLight light = buildTwoPhaseLight();

        // Tick for 34 seconds — past both phases (30s + 3s = 33s total), wraps to phase 0
        light.tick(34.0);

        assertThat(light.getCurrentPhaseIndex()).isZero();
        assertThat(light.getCurrentPhase().getType()).isEqualTo(TrafficLightPhase.PhaseType.GREEN);
    }

    @Test
    void isGreenReturnsTrueForCorrectRoad() {
        TrafficLight light = buildTwoPhaseLight();
        // Phase 0 is GREEN with roads ["r1", "r3"]

        assertThat(light.isGreen("r1")).isTrue();
        assertThat(light.isGreen("r3")).isTrue();
        assertThat(light.isGreen("r2")).isFalse();
    }

    @Test
    void isYellowReturnsTrueForCorrectRoad() {
        TrafficLight light = buildTwoPhaseLight();
        // Advance to YELLOW phase (index 1)
        light.tick(31.0);

        assertThat(light.isYellow("r1")).isTrue();
        assertThat(light.isGreen("r1")).isFalse();
    }

    @Test
    void redForRoadNotInCurrentPhase() {
        TrafficLight light = buildTwoPhaseLight();
        // Phase 0 is GREEN with roads ["r1", "r3"], so "r2" should be RED

        assertThat(light.getSignalState("r2")).isEqualTo("RED");
        assertThat(light.getSignalState("r1")).isEqualTo("GREEN");
    }

    @Test
    void replacePhasesResetsTimer() {
        TrafficLight light = buildTwoPhaseLight();
        // Tick partway through
        light.tick(15.0);
        assertThat(light.getPhaseElapsedMs()).isGreaterThan(0);

        // Replace phases with a single phase
        List<TrafficLightPhase> newPhases = new ArrayList<>();
        newPhases.add(TrafficLightPhase.builder()
            .greenRoadIds(Set.of("r5"))
            .durationMs(20000)
            .type(TrafficLightPhase.PhaseType.GREEN)
            .build());
        light.replacePhases(newPhases);

        assertThat(light.getPhaseElapsedMs()).isZero();
        assertThat(light.getCurrentPhaseIndex()).isZero();
        assertThat(light.getPhases()).hasSize(1);
    }
}
