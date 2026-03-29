package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Vehicle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class PhysicsEngine implements IPhysicsEngine {

    private static final double DELTA = 4.0;   // IDM acceleration exponent
    private static final double S_MIN = 1.0;    // minimum gap guard (metres)
    // Zipper yield removed — was causing adjacent lane congestion at merge point

    /**
     * Advances all vehicles in the lane by one time step using the IDM model.
     * Vehicles are processed front-to-back (descending position) so each vehicle
     * reads its leader's pre-tick position for correct simultaneous-update semantics.
     *
     * @param lane the lane containing vehicles to update
     * @param dt   time step in seconds (e.g. 0.05 for 20 Hz)
     */
    @Override
    public void tick(Lane lane, double dt) {
        tick(lane, dt, -1.0);  // no stop line
    }

    /**
     * Advances vehicles with an optional stop line acting as a virtual stationary leader.
     * Used for red traffic lights and box-blocking prevention.
     *
     * @param lane             the lane containing vehicles
     * @param dt               time step in seconds
     * @param stopLinePosition if >= 0, acts as a stationary virtual leader at this position;
     *                         if < 0, no stop line (normal behavior)
     */
    @Override
    public void tick(Lane lane, double dt, double stopLinePosition) {
        List<Vehicle> vehicles = new ArrayList<>(lane.getVehiclesView());
        if (vehicles.isEmpty()) return;

        // List is already sorted descending by Lane's sorted invariant — no sort needed here

        // Pre-sort obstacles by position for efficient lookup
        List<Obstacle> obstacles = lane.getObstaclesView();

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

            // Determine effective leader candidates: vehicle, obstacle, stop line
            double leaderPos, leaderSpeed, leaderLength;
            boolean hasLeader;

            // Start with existing logic for vehicle + obstacle
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

            // Stop line as virtual stationary leader (position = stopLinePosition, speed = 0, length = 0)
            if (stopLinePosition >= 0 && stopLinePosition > vehicle.getPosition()) {
                if (!hasLeader || stopLinePosition < leaderPos) {
                    leaderPos = stopLinePosition;
                    leaderSpeed = 0.0;
                    leaderLength = 0.0;
                    hasLeader = true;
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

        // Re-sort after position updates to maintain sorted invariant
        lane.resortVehicles();
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
    @Override
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
    @Override
    public double computeFreeFlowAcceleration(Vehicle vehicle) {
        return computeAcceleration(vehicle, 0, 0, 0, false);
    }

}
