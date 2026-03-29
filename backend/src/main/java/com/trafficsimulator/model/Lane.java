package com.trafficsimulator.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
        // Insert maintaining descending position order
        int insertIdx = 0;
        for (int i = 0; i < vehicles.size(); i++) {
            if (vehicles.get(i).getPosition() > v.getPosition()) {
                insertIdx = i + 1;
            } else {
                break;
            }
        }
        vehicles.add(insertIdx, v);
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

    // ── Sorted list maintenance ────────────────────────────────

    /**
     * Re-sorts the vehicle list by position descending.
     * Call once per tick after physics updates positions.
     */
    public void resortVehicles() {
        vehicles.sort(Comparator.comparingDouble(Vehicle::getPosition).reversed());
    }

    // ── Domain query methods ────────────────────────────────────

    /**
     * Returns the vehicle directly ahead of the given vehicle in this lane,
     * or null if none exists.
     * List is sorted descending by position (index 0 = frontmost).
     * Leader is the vehicle at the previous index (lower index = higher position).
     */
    public Vehicle getLeader(Vehicle vehicle) {
        int idx = -1;
        for (int i = 0; i < vehicles.size(); i++) {
            if (vehicles.get(i) == vehicle) { idx = i; break; }
        }
        if (idx <= 0) return null;  // vehicle is at front or not found
        return vehicles.get(idx - 1);
    }

    /**
     * Returns the nearest vehicle ahead of the given position, or null.
     * Used by MOBIL to find the new leader in a target lane.
     * List is sorted descending — scan from back to find first vehicle with pos > position.
     */
    public Vehicle findLeaderAt(double position) {
        for (int i = vehicles.size() - 1; i >= 0; i--) {
            Vehicle v = vehicles.get(i);
            if (v.getPosition() > position) {
                return v;
            }
        }
        return null;
    }

    /**
     * Returns the nearest vehicle behind the given position, or null.
     * Used by MOBIL to find the new follower in a target lane.
     * List is sorted descending — scan from front to find first vehicle with pos < position.
     */
    public Vehicle findFollowerAt(double position) {
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            if (v.getPosition() < position) {
                return v;
            }
        }
        return null;
    }
}
