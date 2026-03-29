package com.trafficsimulator.dto;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimulationStateDtoTest {

    @Test
    void vehicleProjection_horizontalRoad_correctXAndAngleZero() {
        Road road = Road.builder()
            .id("r1").name("Test").length(800.0).speedLimit(33.3)
            .startX(50.0).startY(300.0).endX(850.0).endY(300.0)
            .fromNodeId("n1").toNodeId("n2")
            .lanes(new ArrayList<>())
            .build();

        Lane lane = Lane.builder()
            .id("r1-lane0").laneIndex(0).road(road)
            .length(800.0).maxSpeed(33.3).active(true)
            .build();
        road.getLanes().add(lane);

        Vehicle v = Vehicle.builder()
            .id("v1").position(400.0).speed(20.0).lane(lane).length(4.5)
            .v0(33.3).aMax(1.4).b(2.0).s0(2.0).T(1.5).spawnedAt(0)
            .build();

        // Simulate projection: x = 50 + (400/800) * (850-50) = 50 + 400 = 450
        double fraction = v.getPosition() / road.getLength();
        double expectedX = road.getStartX() + fraction * (road.getEndX() - road.getStartX());
        double expectedAngle = Math.atan2(road.getEndY() - road.getStartY(),
                                          road.getEndX() - road.getStartX());

        assertThat(expectedX).isCloseTo(450.0, within(0.01));
        assertThat(expectedAngle).isCloseTo(0.0, within(0.001));
    }

    @Test
    void simulationStateDto_buildsCorrectly() {
        VehicleDto vehicleDto = VehicleDto.builder()
            .id("v1").roadId("r1").laneId("r1-lane0").laneIndex(0)
            .position(100.0).speed(20.0)
            .laneChangeProgress(1.0).laneChangeSourceIndex(-1)
            .build();

        StatsDto stats = StatsDto.builder()
            .vehicleCount(1).avgSpeed(20.0).density(1.25).throughput(0.0)
            .build();

        SimulationStateDto state = SimulationStateDto.builder()
            .tick(42).timestamp(System.currentTimeMillis())
            .status("RUNNING")
            .vehicles(List.of(vehicleDto))
            .stats(stats)
            .build();

        assertThat(state.getTick()).isEqualTo(42);
        assertThat(state.getStatus()).isEqualTo("RUNNING");
        assertThat(state.getVehicles()).hasSize(1);
        assertThat(state.getVehicles().get(0).getId()).isEqualTo("v1");
        assertThat(state.getStats().getVehicleCount()).isEqualTo(1);
        assertThat(state.getStats().getAvgSpeed()).isEqualTo(20.0);
    }

    @Test
    void stoppedState_hasEmptyVehicleList() {
        SimulationStateDto state = SimulationStateDto.builder()
            .tick(0).timestamp(System.currentTimeMillis())
            .status("STOPPED")
            .vehicles(List.of())
            .stats(StatsDto.builder().vehicleCount(0).avgSpeed(0.0).density(0.0).throughput(0.0).build())
            .build();

        assertThat(state.getVehicles()).isEmpty();
        assertThat(state.getStats().getVehicleCount()).isEqualTo(0);
    }
}
