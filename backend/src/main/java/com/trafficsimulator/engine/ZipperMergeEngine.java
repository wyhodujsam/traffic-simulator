package com.trafficsimulator.engine;

import java.util.HashMap;
import java.util.Map;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

/**
 * Handles zipper merge logic: marks vehicles stuck behind obstacles as merge candidates and
 * rate-limits merges per obstacle.
 */
class ZipperMergeEngine {

    static final double OBSTACLE_PROXIMITY = 30.0; // metres — "stuck behind obstacle" threshold
    static final int ZIPPER_INTERVAL_TICKS = 40; // ticks between zipper merges per obstacle (~2s)
    private static final double STUCK_SPEED_THRESHOLD =
            2.0; // m/s — vehicle considered slow/stopped

    /** Tracks last zipper merge tick per obstacle ID to enforce merge interval */
    private final Map<String, Long> lastZipperMergeTick = new HashMap<>();

    /**
     * Marks the first stopped/slow vehicle behind each obstacle as a zipper merge candidate. Only
     * one vehicle per obstacle gets zipper status -> 1-by-1 merge. Enforces ZIPPER_INTERVAL_TICKS
     * between merges per obstacle.
     */
    void markZipperCandidates(RoadNetwork network, long currentTick) {
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                clearZipperMarks(lane);
                markZipperCandidatesInLane(lane, currentTick);
            }
        }
    }

    /** Clears zipper candidate marks from all vehicles in the lane. */
    private void clearZipperMarks(Lane lane) {
        for (Vehicle v : lane.getVehiclesView()) {
            v.setZipperCandidate(false);
        }
    }

    /**
     * For each obstacle in the lane, marks the closest slow/stopped vehicle as zipper candidate,
     * subject to the merge interval constraint.
     */
    private void markZipperCandidatesInLane(Lane lane, long currentTick) {
        for (Obstacle obs : lane.getObstaclesView()) {
            Long lastMerge = lastZipperMergeTick.get(obs.getId());
            if (lastMerge != null && currentTick - lastMerge < ZIPPER_INTERVAL_TICKS) {
                continue; // too soon since last merge from this obstacle
            }
            Vehicle closest = findClosestVehicleBehindObstacle(lane, obs);
            if (closest != null && closest.getSpeed() < 2.0) {
                closest.setZipperCandidate(true);
            }
        }
    }

    /** Finds the closest vehicle behind the given obstacle within OBSTACLE_PROXIMITY. */
    private Vehicle findClosestVehicleBehindObstacle(Lane lane, Obstacle obs) {
        Vehicle closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Vehicle v : lane.getVehiclesView()) {
            double dist = obs.getPosition() - v.getPosition();
            if (dist > 0 && dist < OBSTACLE_PROXIMITY && dist < closestDist) {
                closest = v;
                closestDist = dist;
            }
        }
        return closest;
    }

    /** Records the tick of a zipper merge for the obstacle this vehicle was queued behind. */
    void recordZipperMerge(Vehicle vehicle, Lane source, long currentTick) {
        for (Obstacle obs : source.getObstaclesView()) {
            double dist = obs.getPosition() - vehicle.getPosition();
            if (dist > -5 && dist < OBSTACLE_PROXIMITY) {
                lastZipperMergeTick.put(obs.getId(), currentTick);
                break;
            }
        }
    }

    /**
     * Returns true if the vehicle is slow/stopped and within OBSTACLE_PROXIMITY of an obstacle
     * ahead.
     */
    boolean isStuckBehindObstacle(Vehicle vehicle, Lane lane) {
        if (vehicle.getSpeed() > STUCK_SPEED_THRESHOLD) {
            return false;
        }
        for (Obstacle obs : lane.getObstaclesView()) {
            double dist = obs.getPosition() - vehicle.getPosition();
            if (dist > 0 && dist < OBSTACLE_PROXIMITY) {
                return true;
            }
        }
        return false;
    }
}
