package com.trafficsimulator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {
    private String id;
    private double position;      // metres from lane start
    private double speed;         // m/s
    private double acceleration;  // m/s² (transient, updated each tick)

    @ToString.Exclude
    private Lane lane;            // live reference to current lane

    private double length;        // metres, default 4.5

    // IDM parameters (assigned at spawn, constant for vehicle lifetime)
    private double v0;    // desired speed m/s
    private double aMax;  // max acceleration m/s²
    private double b;     // comfortable braking deceleration m/s²
    private double s0;    // minimum gap metres
    private double T;     // desired time headway seconds

    private long spawnedAt; // tick number when created

    // Lane change tracking (Phase 7)
    private long lastLaneChangeTick;     // tick number of last lane change, 0 = never
    private boolean forceLaneChange;     // true when lane is closed under this vehicle
    private double laneChangeProgress;   // 0.0 = just changed, 1.0 = settled (for animation)
    @Builder.Default
    private int laneChangeSourceIndex = -1;   // source lane index for y-interpolation (-1 = none)
}
