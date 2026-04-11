package com.trafficsimulator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Vehicle {
    private String id;
    private double position; // metres from lane start
    private double speed; // m/s
    private double acceleration; // m/s² (transient, updated each tick)

    @Setter @ToString.Exclude
    private Lane lane; // live reference to current lane (setter needed for intersection transfers)

    private double length; // metres, default 4.5

    // IDM parameters (assigned at spawn, constant for vehicle lifetime)
    private double v0; // desired speed m/s
    private double aMax; // max acceleration m/s²
    private double b; // comfortable braking deceleration m/s²
    private double s0; // minimum gap metres
    private double timeHeadway; // desired time headway seconds (IDM parameter T)

    private long spawnedAt; // tick number when created

    // Lane change tracking (Phase 7)
    private long lastLaneChangeTick; // tick number of last lane change, 0 = never
    @Setter private boolean forceLaneChange; // true when lane is closed under this vehicle
    private double laneChangeProgress; // 0.0 = just changed, 1.0 = settled (for animation)

    @Builder.Default
    private int laneChangeSourceIndex = -1; // source lane index for y-interpolation (-1 = none)

    // Zipper merge: set per-tick for the first stopped vehicle behind each obstacle
    @Setter private boolean zipperCandidate;

    /**
     * Single mutation point for physics state. Validates invariants. Clamps position to >= 0, speed
     * to [0, v0 * 1.1], acceleration unclamped.
     */
    public void updatePhysics(double position, double speed, double acceleration) {
        this.position = Math.max(0.0, position);
        this.speed =
                Math.max(0.0, Math.min(speed, v0 * 1.1)); // allow 10% overshoot for IDM transients
        this.acceleration = acceleration;
    }

    /** Initiates a lane change to the target lane. Sets animation tracking fields. */
    public void startLaneChange(Lane targetLane, int sourceIndex, long currentTick) {
        this.lane = targetLane;
        this.laneChangeProgress = 0.0;
        this.laneChangeSourceIndex = sourceIndex;
        this.lastLaneChangeTick = currentTick;
    }

    /** Advances lane change animation progress by increment, clamped to [0, 1]. */
    public void advanceLaneChangeProgress(double increment) {
        this.laneChangeProgress = Math.min(1.0, this.laneChangeProgress + increment);
    }

    /** Completes lane change — resets animation tracking. */
    public void completeLaneChange() {
        this.laneChangeSourceIndex = -1;
        this.laneChangeProgress = 0.0;
    }

    /** Returns true if vehicle is currently mid-lane-change. */
    public boolean isInLaneChange() {
        return laneChangeSourceIndex != -1;
    }

    /** Returns true if enough time has passed since last lane change. */
    public boolean canChangeLane(long currentTick, double cooldownTicks) {
        return lastLaneChangeTick == 0 || (currentTick - lastLaneChangeTick) >= cooldownTicks;
    }
}
