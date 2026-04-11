package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trafficsimulator.model.*;

class TrafficLightControllerTest {

    private TrafficLightController controller;

    @BeforeEach
    void setUp() {
        controller = new TrafficLightController();
    }

    private TrafficLight buildTwoPhaseLight(String intersectionId) {
        List<TrafficLightPhase> phases = new ArrayList<>();
        phases.add(
                TrafficLightPhase.builder()
                        .greenRoadIds(Set.of("r_in"))
                        .durationMs(30000)
                        .type(TrafficLightPhase.PhaseType.GREEN)
                        .build());
        phases.add(
                TrafficLightPhase.builder()
                        .greenRoadIds(Set.of("r_in"))
                        .durationMs(3000)
                        .type(TrafficLightPhase.PhaseType.YELLOW)
                        .build());
        return TrafficLight.builder()
                .intersectionId(intersectionId)
                .phases(phases)
                .currentPhaseIndex(0)
                .phaseElapsedMs(0)
                .build();
    }

    @Test
    void advancesAllSignalIntersections() {
        // Create a SIGNAL intersection with a traffic light
        Intersection signalIxtn =
                Intersection.builder()
                        .id("ix_signal")
                        .type(IntersectionType.SIGNAL)
                        .trafficLight(buildTwoPhaseLight("ix_signal"))
                        .build();

        // Create a NONE intersection (no traffic light)
        Intersection noneIxtn =
                Intersection.builder().id("ix_none").type(IntersectionType.NONE).build();

        Map<String, Intersection> intersections = new LinkedHashMap<>();
        intersections.put(signalIxtn.getId(), signalIxtn);
        intersections.put(noneIxtn.getId(), noneIxtn);

        RoadNetwork network =
                RoadNetwork.builder()
                        .id("test")
                        .roads(new LinkedHashMap<>())
                        .intersections(intersections)
                        .spawnPoints(List.of())
                        .despawnPoints(List.of())
                        .build();

        // Tick for 0.05 seconds (50ms)
        controller.tick(0.05, network);

        // SIGNAL light should have advanced
        assertThat(signalIxtn.getTrafficLight().getPhaseElapsedMs()).isEqualTo(50);

        // NONE intersection should have no traffic light
        assertThat(noneIxtn.getTrafficLight()).isNull();
    }

    @Test
    void doesNothingOnEmptyNetwork() {
        RoadNetwork network =
                RoadNetwork.builder()
                        .id("empty")
                        .roads(new LinkedHashMap<>())
                        .intersections(new LinkedHashMap<>())
                        .spawnPoints(List.of())
                        .despawnPoints(List.of())
                        .build();

        assertThatCode(() -> controller.tick(0.05, network)).doesNotThrowAnyException();
    }

    @Test
    void doesNothingOnNullNetwork() {
        assertThatCode(() -> controller.tick(0.05, null)).doesNotThrowAnyException();
    }
}
