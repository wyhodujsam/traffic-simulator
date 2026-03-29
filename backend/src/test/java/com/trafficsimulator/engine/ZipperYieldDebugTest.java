package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ZipperYieldDebugTest {
    @Test
    void debugYield() {
        PhysicsEngine pe = new PhysicsEngine();
        Road road = Road.builder().id("r1").name("R").length(800).speedLimit(33.3)
            .startX(0).startY(300).endX(800).endY(300).fromNodeId("n1").toNodeId("n2")
            .lanes(new ArrayList<>()).build();

        Lane lane0 = Lane.builder().id("l0").laneIndex(0).road(road).length(800).maxSpeed(33.3).active(true).build();
        Lane lane1 = Lane.builder().id("l1").laneIndex(1).road(road).length(800).maxSpeed(33.3).active(true).build();
        road.getLanes().add(lane0);
        road.getLanes().add(lane1);

        // Obstacle on lane1
        lane1.getObstacles().add(Obstacle.builder().id("obs").laneId("l1").position(400).length(3).createdAtTick(0).build());
        // Stuck vehicle on lane1
        Vehicle stuck = Vehicle.builder().id("stuck").position(392).speed(0).acceleration(0)
            .lane(lane1).length(4.5).v0(33.3).aMax(1.4).b(2.0).s0(2.0).T(1.5).spawnedAt(0).laneChangeSourceIndex(-1).build();
        lane1.getVehicles().add(stuck);

        // Free vehicle on lane0
        Vehicle free = Vehicle.builder().id("free").position(370).speed(25).acceleration(0)
            .lane(lane0).length(4.5).v0(33.3).aMax(1.4).b(2.0).s0(2.0).T(1.5).spawnedAt(0).laneChangeSourceIndex(-1).build();
        lane0.getVehicles().add(free);

        // Check road neighbor
        Lane neighbor = road.getLeftNeighbor(lane0);
        System.out.println("Left neighbor of lane0: " + (neighbor != null ? neighbor.getId() : "null"));
        System.out.println("Lane0 road: " + (lane0.getRoad() != null ? lane0.getRoad().getId() : "null"));
        System.out.println("Obstacle count on lane1: " + lane1.getObstacles().size());

        // Run 1 tick
        pe.tick(lane0, 0.05);
        System.out.println("After 1 tick: free speed = " + free.getSpeed() + " pos = " + free.getPosition());

        // Run more ticks
        for (int i = 0; i < 39; i++) {
            pe.tick(lane0, 0.05);
            pe.tick(lane1, 0.05);
        }
        System.out.println("After 40 ticks: free speed = " + free.getSpeed() + " pos = " + free.getPosition());
        assertThat(free.getSpeed()).as("should slow down").isLessThan(15.0);
    }
}
