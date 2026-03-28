package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class Lane {
    private String id;           // globally unique: "r1-lane0"
    private int laneIndex;       // 0-based within parent road

    @ToString.Exclude
    private Road road;           // back-reference to parent road

    private double length;       // metres (copied from road)
    private double maxSpeed;     // m/s
    private boolean active;      // for Phase 7 road narrowing

    @Builder.Default
    private List<Vehicle> vehicles = new ArrayList<>();

    @Builder.Default
    private List<Obstacle> obstacles = new ArrayList<>();

    /**
     * Returns the vehicle directly ahead of the given vehicle in this lane,
     * or null if none exists.
     *
     * <p><b>Performance note:</b> This is an O(n) linear scan over all vehicles in the lane.
     * With high vehicle counts this becomes a hot path at 20 Hz tick rate.
     * Must be replaced with a sorted-list (e.g. {@code TreeMap<Double, Vehicle>}) lookup
     * in Phase 3 (IDM tick implementation) to achieve O(log n) leader lookup.</p>
     */
    public Vehicle getLeader(Vehicle vehicle) {
        Vehicle leader = null;
        for (Vehicle v : vehicles) {
            if (v != vehicle && v.getPosition() > vehicle.getPosition()) {
                if (leader == null || v.getPosition() < leader.getPosition()) {
                    leader = v;
                }
            }
        }
        return leader;
    }

    /**
     * Returns the nearest vehicle ahead of the given position, or null.
     * Used by MOBIL to find the new leader in a target lane.
     */
    public Vehicle findLeaderAt(double position) {
        Vehicle leader = null;
        for (Vehicle v : vehicles) {
            if (v.getPosition() > position) {
                if (leader == null || v.getPosition() < leader.getPosition()) {
                    leader = v;
                }
            }
        }
        return leader;
    }

    /**
     * Returns the nearest vehicle behind the given position, or null.
     * Used by MOBIL to find the new follower in a target lane.
     */
    public Vehicle findFollowerAt(double position) {
        Vehicle follower = null;
        for (Vehicle v : vehicles) {
            if (v.getPosition() < position) {
                if (follower == null || v.getPosition() > follower.getPosition()) {
                    follower = v;
                }
            }
        }
        return follower;
    }
}
