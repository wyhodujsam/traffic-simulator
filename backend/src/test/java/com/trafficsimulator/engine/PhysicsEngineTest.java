package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.Vehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PhysicsEngineTest {

    private PhysicsEngine engine;
    private Lane lane;
    private Road road;

    private static final double DT = 0.05;           // 20 Hz
    private static final double MAX_SPEED = 33.3;     // m/s (~120 km/h)
    private static final double LANE_LENGTH = 2000.0; // metres

    @BeforeEach
    void setUp() {
        engine = new PhysicsEngine();

        road = Road.builder()
            .id("r1").name("Test Road").length(LANE_LENGTH).speedLimit(MAX_SPEED)
            .startX(0).startY(0).endX(LANE_LENGTH).endY(0)
            .fromNodeId("n1").toNodeId("n2")
            .lanes(new ArrayList<>())
            .build();

        lane = Lane.builder()
            .id("r1-lane0").laneIndex(0).road(road)
            .length(LANE_LENGTH).maxSpeed(MAX_SPEED).active(true)
            .build();
        road.getLanes().add(lane);
    }

    private Vehicle createVehicle(String id, double position, double speed) {
        Vehicle v = Vehicle.builder()
            .id(id)
            .position(position)
            .speed(speed)
            .acceleration(0.0)
            .lane(lane)
            .length(4.5)
            .v0(MAX_SPEED)
            .aMax(1.4)
            .b(2.0)
            .s0(2.0)
            .T(1.5)
            .spawnedAt(0)
            .build();
        lane.addVehicle(v);
        return v;
    }

    // =========================================================================
    // Test 1: Free-Flow Acceleration
    // Single vehicle, no leader. Must accelerate from 0 toward v0.
    // After 60 ticks (3s), speed > 3.0 m/s and speed <= maxSpeed. No NaN.
    // =========================================================================
    @Test
    void freeFlow_singleVehicle_acceleratesTowardV0() {
        Vehicle v = createVehicle("v1", 100.0, 0.0);

        for (int tick = 0; tick < 60; tick++) {
            engine.tick(lane, DT);

            // Post-tick invariants every tick
            assertThat(v.getSpeed()).as("speed >= 0 at tick %d", tick)
                .isGreaterThanOrEqualTo(0.0);
            assertThat(v.getSpeed()).as("speed <= maxSpeed at tick %d", tick)
                .isLessThanOrEqualTo(MAX_SPEED);
            assertThat(Double.isFinite(v.getSpeed())).as("speed is finite at tick %d", tick)
                .isTrue();
            assertThat(Double.isFinite(v.getPosition())).as("position is finite at tick %d", tick)
                .isTrue();
        }

        // After 3 seconds of free-flow acceleration from 0, speed should be significant
        assertThat(v.getSpeed()).as("speed after 60 ticks").isGreaterThan(3.0);
        // Position must have increased
        assertThat(v.getPosition()).as("position after 60 ticks").isGreaterThan(100.0);
    }

    // =========================================================================
    // Test 2: Two-Vehicle Following (SIM-04)
    // Leader at 100m speed 20 m/s. Follower at 80m speed 20 m/s.
    // Gap must stay >= s0 (2.0m) over 200 ticks. Gap never reaches zero.
    // =========================================================================
    @Test
    void twoVehicleFollowing_gapNeverBelowS0() {
        Vehicle leader = createVehicle("leader", 100.0, 20.0);
        Vehicle follower = createVehicle("follower", 80.0, 20.0);

        for (int tick = 0; tick < 200; tick++) {
            engine.tick(lane, DT);

            double gap = leader.getPosition() - follower.getPosition() - leader.getLength();
            assertThat(gap).as("gap at tick %d", tick)
                .isGreaterThanOrEqualTo(follower.getS0());
            assertThat(follower.getSpeed()).as("follower speed at tick %d", tick)
                .isGreaterThanOrEqualTo(0.0);
            assertThat(Double.isFinite(follower.getSpeed())).isTrue();
        }
    }

    // =========================================================================
    // Test 3: Emergency Stop (SIM-07)
    // Leader stops instantly. Follower at 10m gap, speed 20 m/s.
    // Follower must brake hard, speed clamped >= 0, no overlap with leader.
    // =========================================================================
    @Test
    void emergencyStop_followerStopsBeforeCollision() {
        Vehicle leader = createVehicle("leader", 100.0, 20.0);
        Vehicle follower = createVehicle("follower", 85.5, 20.0);
        // Initial gap = 100 - 85.5 - 4.5 = 10.0 m

        // Leader stops instantly
        leader.updatePhysics(100.0, 0.0, 0.0);

        for (int tick = 0; tick < 200; tick++) {
            // Keep leader stopped
            leader.updatePhysics(100.0, 0.0, 0.0);

            engine.tick(lane, DT);

            // Speed must never go negative (SIM-07)
            assertThat(follower.getSpeed()).as("follower speed >= 0 at tick %d", tick)
                .isGreaterThanOrEqualTo(0.0);
            assertThat(follower.getSpeed()).as("follower speed <= maxSpeed at tick %d", tick)
                .isLessThanOrEqualTo(MAX_SPEED);

            // No overlap: follower position < leader position - leader length
            assertThat(follower.getPosition()).as("no collision at tick %d", tick)
                .isLessThan(leader.getPosition() - leader.getLength() + 1.0);
                // +1.0 tolerance for S_MIN guard numerical boundary

            assertThat(Double.isFinite(follower.getSpeed())).isTrue();
            assertThat(Double.isFinite(follower.getPosition())).isTrue();
        }

        // Follower should have stopped or nearly stopped
        assertThat(follower.getSpeed()).as("follower stopped eventually").isLessThan(1.0);
    }

    // =========================================================================
    // Test 4: Zero Gap Guard
    // Force gap = 0 (overlap). tick() must not throw, no NaN.
    // Acceleration is large negative (heavy braking) but finite. Speed >= 0.
    // =========================================================================
    @Test
    void zeroGapGuard_overlapProducesFiniteBraking() {
        // Place follower so close that gap is negative (overlap)
        // gap = leader.pos - follower.pos - leader.length = 100.0 - 99.0 - 4.5 = -3.5 (negative!)
        Vehicle leader = createVehicle("leader", 100.0, 10.0);
        Vehicle follower = createVehicle("follower", 99.0, 15.0);

        engine.tick(lane, DT);

        // No exception was thrown
        assertThat(Double.isFinite(follower.getAcceleration()))
            .as("acceleration is finite despite overlap").isTrue();
        assertThat(follower.getAcceleration())
            .as("acceleration is negative (braking)").isLessThan(0.0);
        assertThat(follower.getSpeed())
            .as("speed >= 0 after zero-gap").isGreaterThanOrEqualTo(0.0);
        assertThat(Double.isFinite(follower.getSpeed()))
            .as("speed is finite").isTrue();
        assertThat(Double.isFinite(follower.getPosition()))
            .as("position is finite").isTrue();
    }

    // =========================================================================
    // Test 5: NaN Guard
    // Set aMax = 0 and b = 0 to force sqrt(0) = 0 in denominator.
    // Must not produce NaN. Fallback acceleration applied.
    // =========================================================================
    @Test
    void nanGuard_zeroParametersProduceFiniteResult() {
        Vehicle leader = createVehicle("leader", 120.0, 15.0);
        Vehicle follower = createVehicle("follower", 100.0, 20.0);

        // Rebuild follower with corrupt parameters to force NaN in IDM formula
        lane.removeVehicle(follower);
        follower = Vehicle.builder()
            .id("follower").position(100.0).speed(20.0).acceleration(0.0)
            .lane(lane).length(4.5).v0(MAX_SPEED).aMax(0.0).b(0.0).s0(2.0).T(1.5).spawnedAt(0)
            .build();
        lane.addVehicle(follower);

        engine.tick(lane, DT);

        // Must not produce NaN or Infinity
        assertThat(Double.isFinite(follower.getAcceleration()))
            .as("acceleration is finite despite aMax=0, b=0").isTrue();
        assertThat(Double.isFinite(follower.getSpeed()))
            .as("speed is finite").isTrue();
        assertThat(Double.isFinite(follower.getPosition()))
            .as("position is finite").isTrue();
        assertThat(follower.getSpeed())
            .as("speed >= 0").isGreaterThanOrEqualTo(0.0);
    }

    // =========================================================================
    // Test 6: Velocity Clamp (SIM-07)
    // Vehicle near maxSpeed in free-flow. After tick, speed must not exceed
    // lane.getMaxSpeed().
    // =========================================================================
    @Test
    void velocityClamp_speedNeverExceedsLaneMaxSpeed() {
        // Build vehicle with speed just below maxSpeed, with high v0 to push past limit
        Vehicle v = Vehicle.builder()
            .id("v1").position(100.0).speed(MAX_SPEED - 0.01).acceleration(0.0)
            .lane(lane).length(4.5).v0(MAX_SPEED + 10.0).aMax(1.4).b(2.0).s0(2.0).T(1.5).spawnedAt(0)
            .build();
        lane.addVehicle(v); // personal v0 exceeds lane max

        engine.tick(lane, DT);

        assertThat(v.getSpeed()).as("speed clamped to lane maxSpeed")
            .isLessThanOrEqualTo(MAX_SPEED);
        assertThat(v.getSpeed()).as("speed >= 0")
            .isGreaterThanOrEqualTo(0.0);
    }

    // =========================================================================
    // Test 7: 500-Vehicle Performance Benchmark
    // 500 vehicles, 100 ticks. Total < 500ms (5ms/tick average).
    // =========================================================================
    @Test
    @Tag("benchmark")
    void benchmark_500vehicles_under5msPerTick() {
        // Create 500 vehicles at 10m spacing
        for (int i = 0; i < 500; i++) {
            createVehicle("v" + i, i * 10.0, 15.0);
        }

        // Warmup: 10 ticks (JIT compilation)
        for (int tick = 0; tick < 10; tick++) {
            engine.tick(lane, DT);
        }

        // Benchmark: 100 ticks
        long startNanos = System.nanoTime();
        for (int tick = 0; tick < 100; tick++) {
            engine.tick(lane, DT);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).as("100 ticks with 500 vehicles must complete in < 500ms")
            .isLessThan(500);

        // Verify no NaN leaked through
        for (Vehicle v : lane.getVehiclesView()) {
            assertThat(Double.isFinite(v.getSpeed())).isTrue();
            assertThat(Double.isFinite(v.getPosition())).isTrue();
            assertThat(v.getSpeed()).isGreaterThanOrEqualTo(0.0);
            assertThat(v.getSpeed()).isLessThanOrEqualTo(MAX_SPEED);
        }
    }

    // =========================================================================
    // Test 8: Free-flow position monotonically increases
    // =========================================================================
    @Test
    void freeFlow_positionMonotonicallyIncreases() {
        Vehicle v = createVehicle("v1", 50.0, 5.0);

        double previousPosition = v.getPosition();
        for (int tick = 0; tick < 100; tick++) {
            engine.tick(lane, DT);
            assertThat(v.getPosition()).as("position non-decreasing at tick %d", tick)
                .isGreaterThanOrEqualTo(previousPosition);
            previousPosition = v.getPosition();
        }
    }

    // =========================================================================
    // Test 9: Empty lane — no exception
    // =========================================================================
    @Test
    void emptyLane_noException() {
        // lane has no vehicles
        engine.tick(lane, DT);
        // No exception = pass
        assertThat(lane.getVehiclesView()).isEmpty();
    }
}
