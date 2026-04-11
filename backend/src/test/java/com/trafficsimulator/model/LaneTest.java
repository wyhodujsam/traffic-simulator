package com.trafficsimulator.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LaneTest {

    private Lane lane;
    private Road road;

    @BeforeEach
    void setUp() {
        road =
                Road.builder()
                        .id("r1")
                        .name("Test Road")
                        .length(2000.0)
                        .speedLimit(33.3)
                        .startX(0)
                        .startY(0)
                        .endX(2000)
                        .endY(0)
                        .build();

        lane =
                Lane.builder()
                        .id("r1-lane0")
                        .laneIndex(0)
                        .road(road)
                        .length(2000.0)
                        .maxSpeed(33.3)
                        .active(true)
                        .build();
    }

    private Vehicle vehicle(String id, double position) {
        return Vehicle.builder()
                .id(id)
                .position(position)
                .speed(10.0)
                .length(4.5)
                .v0(33.3)
                .aMax(1.4)
                .b(2.0)
                .s0(2.0)
                .timeHeadway(1.5)
                .lane(lane)
                .build();
    }

    @Test
    void testAddVehicle_maintainsSortOrder() {
        Vehicle v1 = vehicle("v1", 10);
        Vehicle v2 = vehicle("v2", 30);
        Vehicle v3 = vehicle("v3", 20);

        lane.addVehicle(v1);
        lane.addVehicle(v2);
        lane.addVehicle(v3);

        List<Vehicle> view = lane.getVehiclesView();
        assertThat(view).extracting(Vehicle::getPosition).containsExactly(30.0, 20.0, 10.0);
    }

    @Test
    void testGetLeader_returnsClosestAhead() {
        Vehicle v1 = vehicle("v1", 10);
        Vehicle v2 = vehicle("v2", 20);
        Vehicle v3 = vehicle("v3", 30);

        lane.addVehicle(v1);
        lane.addVehicle(v2);
        lane.addVehicle(v3);

        assertThat(lane.getLeader(v1)).isSameAs(v2);
    }

    @Test
    void testGetLeader_frontVehicle_returnsNull() {
        Vehicle v1 = vehicle("v1", 10);
        Vehicle v2 = vehicle("v2", 20);
        Vehicle v3 = vehicle("v3", 30);

        lane.addVehicle(v1);
        lane.addVehicle(v2);
        lane.addVehicle(v3);

        assertThat(lane.getLeader(v3)).isNull();
    }

    @Test
    void testFindLeaderAt_position() {
        Vehicle v1 = vehicle("v1", 10);
        Vehicle v2 = vehicle("v2", 20);
        Vehicle v3 = vehicle("v3", 30);

        lane.addVehicle(v1);
        lane.addVehicle(v2);
        lane.addVehicle(v3);

        // Nearest vehicle ahead of position 15 should be v2 (at 20)
        assertThat(lane.findLeaderAt(15)).isSameAs(v2);
    }

    @Test
    void testFindFollowerAt_position() {
        Vehicle v1 = vehicle("v1", 10);
        Vehicle v2 = vehicle("v2", 20);
        Vehicle v3 = vehicle("v3", 30);

        lane.addVehicle(v1);
        lane.addVehicle(v2);
        lane.addVehicle(v3);

        // Nearest vehicle behind position 25 should be v2 (at 20)
        assertThat(lane.findFollowerAt(25)).isSameAs(v2);
    }

    @Test
    void testResortVehicles_afterPositionChange() {
        Vehicle v1 = vehicle("v1", 10);
        Vehicle v2 = vehicle("v2", 20);
        Vehicle v3 = vehicle("v3", 30);

        lane.addVehicle(v1);
        lane.addVehicle(v2);
        lane.addVehicle(v3);

        // Manually change positions (simulating physics update)
        v1.updatePhysics(35, v1.getSpeed(), 0); // v1 overtakes v3
        v2.updatePhysics(25, v2.getSpeed(), 0);

        lane.resortVehicles();

        List<Vehicle> view = lane.getVehiclesView();
        assertThat(view).extracting(Vehicle::getPosition).containsExactly(35.0, 30.0, 25.0);
        assertThat(view.get(0)).isSameAs(v1);
    }

    @Test
    @Tag("performance")
    void testPerformance_500vehicles_100ticks() {
        // Create lane with 500 vehicles
        for (int i = 0; i < 500; i++) {
            lane.addVehicle(vehicle("v" + i, i * 10.0));
        }

        long start = System.nanoTime();
        for (int tick = 0; tick < 100; tick++) {
            List<Vehicle> view = lane.getVehiclesView();
            for (Vehicle v : view) {
                lane.getLeader(v);
            }
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsed)
                .as("500 vehicles x 100 getLeader() calls should be under 500ms")
                .isLessThan(500);
    }
}
