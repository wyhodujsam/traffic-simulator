package com.trafficsimulator.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

@Getter
@Setter
@EqualsAndHashCode
@ToString
@Builder
public class Lane {
    private String id;           // globally unique: "r1-lane0"
    private int laneIndex;       // 0-based within parent road

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Road road;           // back-reference to parent road

    private double length;       // metres (copied from road)
    private double maxSpeed;     // m/s
    private boolean active;      // for Phase 7 road narrowing

    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private List<Vehicle> vehicles = new ArrayList<>();

    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private List<Obstacle> obstacles = new ArrayList<>();

    // ── Vehicle collection methods ──────────────────────────────

/** Returns an unmodifiable view of the vehicles list. */
    public List<Vehicle> getVehiclesView() {
        return Collections.unmodifiableList(vehicles);
    }

    public void addVehicle(Vehicle v) {
        vehicles.add(v);
    }

    public void removeVehicle(Vehicle v) {
        vehicles.remove(v);
    }

    public boolean removeVehiclesIf(Predicate<Vehicle> predicate) {
        return vehicles.removeIf(predicate);
    }

    public int getVehicleCount() {
        return vehicles.size();
    }

    public void clearVehicles() {
        vehicles.clear();
    }

    // ── Obstacle collection methods ─────────────────────────────

/** Returns an unmodifiable view of the obstacles list. */
    public List<Obstacle> getObstaclesView() {
        return Collections.unmodifiableList(obstacles);
    }

    public void addObstacle(Obstacle o) {
        obstacles.add(o);
    }

    public void removeObstacle(Obstacle o) {
        obstacles.remove(o);
    }

    public boolean removeObstaclesIf(Predicate<Obstacle> predicate) {
        return obstacles.removeIf(predicate);
    }

    public void clearObstacles() {
        obstacles.clear();
    }

    // ── Domain query methods ────────────────────────────────────

    /**
     * Returns the vehicle directly ahead of the given vehicle in this lane,
     * or null if none exists.
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
