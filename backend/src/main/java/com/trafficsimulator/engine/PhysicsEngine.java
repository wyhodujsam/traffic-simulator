package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.Vehicle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@Slf4j
public class PhysicsEngine {

    private static final double DELTA = 4.0;   // IDM acceleration exponent
    private static final double S_MIN = 1.0;    // minimum gap guard (metres)
    private static final double ZIPPER_YIELD_DISTANCE = 80.0; // metres ahead to start yielding
    private static final double ZIPPER_YIELD_SPEED = 5.0;     // m/s — virtual leader speed at yield point

    /**
     * Advances all vehicles in the lane by one time step using the IDM model.
     * Vehicles are processed front-to-back (descending position) so each vehicle
     * reads its leader's pre-tick position for correct simultaneous-update semantics.
     *
     * @param lane the lane containing vehicles to update
     * @param dt   time step in seconds (e.g. 0.05 for 20 Hz)
     */
    public void tick(Lane lane, double dt) {
        List<Vehicle> vehicles = lane.getVehicles();
        if (vehicles.isEmpty()) return;

        // Sort by position descending — front vehicles first
        vehicles.sort(Comparator.comparingDouble(Vehicle::getPosition).reversed());

        // Pre-sort obstacles by position for efficient lookup
        List<Obstacle> obstacles = lane.getObstacles();

        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle vehicle = vehicles.get(i);

            // Find vehicle leader (existing logic)
            Vehicle vehicleLeader = (i > 0) ? vehicles.get(i - 1) : null;

            // Find nearest obstacle ahead
            Obstacle nearestObstacle = null;
            double nearestObstaclePos = Double.MAX_VALUE;
            for (Obstacle obs : obstacles) {
                if (obs.getPosition() > vehicle.getPosition() && obs.getPosition() < nearestObstaclePos) {
                    nearestObstacle = obs;
                    nearestObstaclePos = obs.getPosition();
                }
            }

            // Determine effective leader: vehicle or obstacle, whichever is closer
            double leaderPos, leaderSpeed, leaderLength;
            boolean hasLeader;

            if (vehicleLeader != null && nearestObstacle != null) {
                // Both exist — pick the closer one
                if (vehicleLeader.getPosition() <= nearestObstaclePos) {
                    leaderPos = vehicleLeader.getPosition();
                    leaderSpeed = vehicleLeader.getSpeed();
                    leaderLength = vehicleLeader.getLength();
                } else {
                    leaderPos = nearestObstacle.getPosition();
                    leaderSpeed = 0.0;
                    leaderLength = nearestObstacle.getLength();
                }
                hasLeader = true;
            } else if (vehicleLeader != null) {
                leaderPos = vehicleLeader.getPosition();
                leaderSpeed = vehicleLeader.getSpeed();
                leaderLength = vehicleLeader.getLength();
                hasLeader = true;
            } else if (nearestObstacle != null) {
                leaderPos = nearestObstacle.getPosition();
                leaderSpeed = 0.0;
                leaderLength = nearestObstacle.getLength();
                hasLeader = true;
            } else {
                leaderPos = 0;
                leaderSpeed = 0;
                leaderLength = 0;
                hasLeader = false;
            }

            // Zipper yield: if adjacent lane has obstacle with stuck vehicles,
            // treat the obstacle position as a slow virtual leader to create merge gaps
            Road road = lane.getRoad();
            if (road != null) {
                double zipperLeaderPos = findZipperYieldPoint(vehicle, lane, road);
                if (zipperLeaderPos < Double.MAX_VALUE) {
                    // Only yield if this virtual leader is closer than the real one
                    if (!hasLeader || zipperLeaderPos < leaderPos) {
                        leaderPos = zipperLeaderPos;
                        leaderSpeed = ZIPPER_YIELD_SPEED;
                        leaderLength = 3.0;
                        hasLeader = true;
                    }
                }
            }

            double acceleration = computeAcceleration(vehicle, leaderPos, leaderSpeed, leaderLength, hasLeader);

            // Guard 4: NaN / Infinity fallback
            if (!Double.isFinite(acceleration)) {
                log.warn("NaN acceleration guard triggered for vehicle={} aMax={} b={}",
                    vehicle.getId(), vehicle.getAMax(), vehicle.getB());
                acceleration = -vehicle.getB();
                // If b is also non-finite, fall back to zero
                if (!Double.isFinite(acceleration)) {
                    acceleration = 0.0;
                }
            }

            // Semi-implicit Euler integration
            double newSpeed = vehicle.getSpeed() + acceleration * dt;

            // Guard 2: Negative speed clamp
            newSpeed = Math.max(0.0, newSpeed);

            // Guard 3: maxSpeed clamp (SIM-07)
            newSpeed = Math.min(newSpeed, lane.getMaxSpeed());

            double newPosition = vehicle.getPosition() + newSpeed * dt;

            // Guard 6: Position clamp — never pass the effective leader
            if (hasLeader) {
                double maxPosition = leaderPos - leaderLength - S_MIN;
                if (newPosition > maxPosition) {
                    newPosition = maxPosition;
                    newSpeed = 0.0;
                }
            }

            // Write back
            vehicle.setAcceleration(acceleration);
            vehicle.setSpeed(newSpeed);
            vehicle.setPosition(newPosition);
        }
    }

    /**
     * Computes IDM acceleration for a vehicle given optional leader data.
     * Leader can be a vehicle or an obstacle — represented as position/speed/length primitives.
     *
     * <p>IDM formula: a = aMax * [1 - (v/v0)^delta - (sStar/s)^2]
     *
     * <p>where sStar = s0 + max(0, v*T + v*deltaV / (2*sqrt(aMax*b)))
     *
     * @param vehicle       the following vehicle
     * @param leaderPosition position of leader's front (metres from lane start), or -1 if no leader
     * @param leaderSpeed    leader's speed (m/s), 0 for obstacles
     * @param leaderLength   leader's length (metres)
     * @param hasLeader      true if a leader exists
     */
    public double computeAcceleration(Vehicle vehicle, double leaderPosition,
                                        double leaderSpeed, double leaderLength,
                                        boolean hasLeader) {
        double v = vehicle.getSpeed();
        double v0 = vehicle.getV0();
        double aMax = vehicle.getAMax();

        // Free-road term: (v / v0)^delta
        double vRatio = v / v0;
        double freeRoadTerm = vRatio * vRatio * vRatio * vRatio; // (v/v0)^4

        if (!hasLeader) {
            // Free-flow: no interaction term
            return aMax * (1.0 - freeRoadTerm);
        }

        // Gap computation (front-bumper to rear-bumper)
        double gap = leaderPosition - vehicle.getPosition() - leaderLength;

        // Guard 1: Zero / negative gap clamp
        double safeGap = Math.max(gap, S_MIN);

        // Speed difference (positive when follower is faster than leader)
        double deltaV = v - leaderSpeed;

        // Desired gap s*
        // Guard 5: s* floor via max(0, ...) on the dynamic term
        double sqrtTerm = 2.0 * Math.sqrt(aMax * vehicle.getB());
        double interactionTerm = (sqrtTerm > 0.0) ? (v * deltaV / sqrtTerm) : 0.0;
        double sStar = vehicle.getS0() + Math.max(0.0, v * vehicle.getT() + interactionTerm);

        // IDM acceleration
        double sRatio = sStar / safeGap;
        return aMax * (1.0 - freeRoadTerm - sRatio * sRatio);
    }

    /**
     * Computes IDM free-flow acceleration (no leader).
     */
    public double computeFreeFlowAcceleration(Vehicle vehicle) {
        return computeAcceleration(vehicle, 0, 0, 0, false);
    }

    /**
     * Finds a zipper yield point: if an adjacent lane has an obstacle with a
     * stuck zipper candidate behind it, returns the obstacle position so this
     * vehicle slows down and creates a merge gap.
     *
     * @return obstacle position to yield at, or Double.MAX_VALUE if no yield needed
     */
    private double findZipperYieldPoint(Vehicle vehicle, Lane currentLane, Road road) {
        double pos = vehicle.getPosition();
        double bestYieldPos = Double.MAX_VALUE;

        // Check adjacent lanes
        for (Lane neighbor : new Lane[]{road.getLeftNeighbor(currentLane), road.getRightNeighbor(currentLane)}) {
            if (neighbor == null) continue;

            for (Obstacle obs : neighbor.getObstacles()) {
                double dist = obs.getPosition() - pos;
                // Only yield if obstacle is ahead and within yield distance
                if (dist > 0 && dist < ZIPPER_YIELD_DISTANCE) {
                    // Check if there's actually a stuck vehicle behind this obstacle
                    boolean hasStuckVehicle = false;
                    for (Vehicle v : neighbor.getVehicles()) {
                        double vDist = obs.getPosition() - v.getPosition();
                        if (vDist > 0 && vDist < 30.0 && v.getSpeed() < 2.0) {
                            hasStuckVehicle = true;
                            break;
                        }
                    }
                    if (hasStuckVehicle && obs.getPosition() < bestYieldPos) {
                        bestYieldPos = obs.getPosition();
                    }
                }
            }
        }

        return bestYieldPos;
    }
}
