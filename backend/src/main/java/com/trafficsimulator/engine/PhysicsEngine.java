package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
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

        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle vehicle = vehicles.get(i);

            // Find leader: next vehicle ahead (index i-1 after descending sort)
            // The vehicle at index 0 has no leader (it is the frontmost)
            Vehicle leader = (i > 0) ? vehicles.get(i - 1) : null;

            double acceleration = computeAcceleration(vehicle, leader);

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

            // Write back
            vehicle.setAcceleration(acceleration);
            vehicle.setSpeed(newSpeed);
            vehicle.setPosition(newPosition);
        }
    }

    /**
     * Computes IDM acceleration for a vehicle, optionally following a leader.
     *
     * <p>IDM formula: a = aMax * [1 - (v/v0)^delta - (sStar/s)^2]
     *
     * <p>where sStar = s0 + max(0, v*T + v*deltaV / (2*sqrt(aMax*b)))
     */
    private double computeAcceleration(Vehicle vehicle, Vehicle leader) {
        double v = vehicle.getSpeed();
        double v0 = vehicle.getV0();
        double aMax = vehicle.getAMax();

        // Free-road term: (v / v0)^delta
        double vRatio = v / v0;
        double freeRoadTerm = vRatio * vRatio * vRatio * vRatio; // (v/v0)^4

        if (leader == null) {
            // Free-flow: no interaction term
            return aMax * (1.0 - freeRoadTerm);
        }

        // Gap computation (front-bumper to rear-bumper)
        double gap = leader.getPosition() - vehicle.getPosition() - leader.getLength();

        // Guard 1: Zero / negative gap clamp
        double safeGap = Math.max(gap, S_MIN);

        // Speed difference (positive when follower is faster than leader)
        double deltaV = v - leader.getSpeed();

        // Desired gap s*
        // Guard 5: s* floor via max(0, ...) on the dynamic term
        double sqrtTerm = 2.0 * Math.sqrt(aMax * vehicle.getB());
        double interactionTerm = (sqrtTerm > 0.0) ? (v * deltaV / sqrtTerm) : 0.0;
        double sStar = vehicle.getS0() + Math.max(0.0, v * vehicle.getT() + interactionTerm);

        // IDM acceleration
        double sRatio = sStar / safeGap;
        return aMax * (1.0 - freeRoadTerm - sRatio * sRatio);
    }
}
