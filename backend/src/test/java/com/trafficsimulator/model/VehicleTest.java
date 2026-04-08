package com.trafficsimulator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleTest {

    private Vehicle createVehicle(double v0) {
        return Vehicle.builder()
            .id("v1")
            .position(100.0)
            .speed(10.0)
            .acceleration(0.0)
            .length(4.5)
            .v0(v0)
            .aMax(1.4)
            .b(2.0)
            .s0(2.0)
            .timeHeadway(1.5)
            .spawnedAt(0)
            .build();
    }

    @Test
    void testUpdatePhysics_clampsNegativeSpeed() {
        Vehicle v = createVehicle(33.3);
        v.updatePhysics(50.0, -5.0, -1.0);
        assertThat(v.getSpeed()).isZero();
        assertThat(v.getPosition()).isEqualTo(50.0);
        assertThat(v.getAcceleration()).isEqualTo(-1.0);
    }

    @Test
    void testUpdatePhysics_clampsNegativePosition() {
        Vehicle v = createVehicle(33.3);
        v.updatePhysics(-1.0, 10.0, 0.5);
        assertThat(v.getPosition()).isZero();
    }

    @Test
    void testUpdatePhysics_clampsExcessiveSpeed() {
        double v0 = 33.3;
        Vehicle v = createVehicle(v0);
        v.updatePhysics(50.0, v0 * 2.0, 0.0);
        assertThat(v.getSpeed()).isEqualTo(v0 * 1.1);
    }

    @Test
    void testStartLaneChange_setsFields() {
        Vehicle v = createVehicle(33.3);
        Lane targetLane = Lane.builder().id("lane-1").length(500).laneIndex(1).build();

        v.startLaneChange(targetLane, 0, 42L);

        assertThat(v.getLane()).isSameAs(targetLane);
        assertThat(v.getLaneChangeSourceIndex()).isZero();
        assertThat(v.getLaneChangeProgress()).isZero();
        assertThat(v.getLastLaneChangeTick()).isEqualTo(42L);
    }

    @Test
    void testAdvanceLaneChangeProgress_clampsToOne() {
        Vehicle v = createVehicle(33.3);
        v.startLaneChange(null, 0, 1L);

        v.advanceLaneChangeProgress(0.5);
        assertThat(v.getLaneChangeProgress()).isEqualTo(0.5);

        v.advanceLaneChangeProgress(0.5);
        assertThat(v.getLaneChangeProgress()).isEqualTo(1.0);

        // Further increment stays at 1.0
        v.advanceLaneChangeProgress(0.3);
        assertThat(v.getLaneChangeProgress()).isEqualTo(1.0);
    }

    @Test
    void testCompleteLaneChange_resetsFields() {
        Vehicle v = createVehicle(33.3);
        v.startLaneChange(null, 2, 10L);
        v.advanceLaneChangeProgress(1.0);

        v.completeLaneChange();

        assertThat(v.getLaneChangeSourceIndex()).isEqualTo(-1);
        assertThat(v.getLaneChangeProgress()).isZero();
    }

    @Test
    void testCanChangeLane_respectsCooldown() {
        Vehicle v = createVehicle(33.3);
        v.startLaneChange(null, 0, 100L);
        v.completeLaneChange();

        // Within cooldown (60 ticks)
        assertThat(v.canChangeLane(150L, 60.0)).isFalse();

        // Exactly at cooldown boundary
        assertThat(v.canChangeLane(160L, 60.0)).isTrue();

        // After cooldown
        assertThat(v.canChangeLane(200L, 60.0)).isTrue();
    }

    @Test
    void testIsInLaneChange() {
        Vehicle v = createVehicle(33.3);

        // Default: not in lane change (sourceIndex = -1 by builder default)
        assertThat(v.isInLaneChange()).isFalse();

        // Start lane change -> in lane change
        v.startLaneChange(null, 0, 1L);
        assertThat(v.isInLaneChange()).isTrue();

        // Complete lane change -> not in lane change
        v.completeLaneChange();
        assertThat(v.isInLaneChange()).isFalse();
    }
}
