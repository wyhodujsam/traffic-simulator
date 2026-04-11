package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Vehicle;

/**
 * Implements the MOBIL (Minimizing Overall Braking Induced by Lane change) model for evaluating
 * lane-change safety and incentive criteria.
 */
class MOBILCalculator {

    // MOBIL parameters
    static final double B_SAFE = 4.0; // safe braking limit m/s^2
    static final double B_SAFE_ZIPPER = 5.5; // relaxed braking limit for zipper merge
    static final double POLITENESS = 0.3; // weight for neighbors' disadvantage
    static final double A_THRESHOLD_LEFT = 0.3; // threshold for moving left (overtake)
    static final double A_THRESHOLD_RIGHT = 0.1; // threshold for moving right (keep-right)

    private final IPhysicsEngine physicsEngine;

    MOBILCalculator(IPhysicsEngine physicsEngine) {
        this.physicsEngine = physicsEngine;
    }

    /** Record for a lane-change intent before conflict resolution. */
    record LaneChangeIntent(
            Vehicle vehicle,
            Lane sourceLane,
            Lane targetLane,
            double position,
            double incentiveScore) {}

    /** Effective leader data resolved from vehicle leader and/or obstacle. */
    private record EffectiveLeader(double position, double speed, double length) {}

    /** Context for MOBIL incentive computation. */
    private record IncentiveContext(
            Vehicle subject, Lane currentLane, Vehicle currentLeader, double subjectPos) {}

    /** Acceleration data for subject in current vs target lane. */
    private record AccelerationPair(double current, double target) {}

    /** Target lane context for MOBIL incentive evaluation. */
    private record TargetLaneContext(
            Vehicle newLeader, Vehicle newFollower, double aThreshold, Lane targetLane) {}

    /**
     * Evaluates MOBIL safety + incentive criteria for one vehicle moving to targetLane. Returns an
     * intent if the move is both safe and beneficial, null otherwise.
     *
     * <p>For forced lane changes (closed lane), only safety criterion is checked.
     */
    LaneChangeIntent evaluateMOBIL(
            Vehicle subject, Lane currentLane, Lane targetLane, double aThreshold) {
        double subjectPos = subject.getPosition();

        Vehicle newLeader = targetLane.findLeaderAt(subjectPos);
        Vehicle newFollower = targetLane.findFollowerAt(subjectPos);
        Vehicle currentLeader = currentLane.getLeader(subject);

        double aSubjectCurrent = computeIdmAccelInLane(subject, currentLeader, currentLane);
        double aSubjectTarget = computeIdmAccelInLane(subject, newLeader, targetLane);

        boolean isZipper = subject.isZipperCandidate();
        if (!isSafeLaneChange(subject, newFollower, isZipper)) {
            return null;
        }
        if (!checkGapSafety(subject, subjectPos, newLeader, newFollower, isZipper)) {
            return null;
        }

        if (hasObstacleConflictInTarget(subject, subjectPos, targetLane)) {
            return null;
        }

        if (subject.isForceLaneChange() || isZipper) {
            double score = subject.isForceLaneChange() ? Double.MAX_VALUE : 100.0;
            return new LaneChangeIntent(subject, currentLane, targetLane, subjectPos, score);
        }

        var ctx = new IncentiveContext(subject, currentLane, currentLeader, subjectPos);
        var accel = new AccelerationPair(aSubjectCurrent, aSubjectTarget);
        var tlCtx = new TargetLaneContext(newLeader, newFollower, aThreshold, targetLane);
        return evaluateIncentive(ctx, accel, tlCtx);
    }

    /**
     * Safety criterion: new follower must not brake harder than b_safe. Zipper candidates skip this
     * check (follower will brake naturally via IDM).
     */
    private boolean isSafeLaneChange(Vehicle subject, Vehicle newFollower, boolean isZipper) {
        if (newFollower == null || isZipper) {
            return true;
        }
        double aNewFollowerAfter = computeIdmAccelWithLeader(newFollower, subject);
        return aNewFollowerAfter >= -B_SAFE;
    }

    /**
     * Gap check: ensures minimum gap to vehicles in target lane. Zipper merges only check ahead gap
     * (follower will brake via IDM after merge).
     */
    private boolean checkGapSafety(
            Vehicle subject,
            double subjectPos,
            Vehicle newLeader,
            Vehicle newFollower,
            boolean isZipper) {
        if (isZipper) {
            return checkZipperGap(subject, subjectPos, newLeader);
        }

        if (newLeader != null) {
            double gapAhead = newLeader.getPosition() - subjectPos - newLeader.getLength();
            if (gapAhead < subject.getS0() + subject.getLength()) {
                return false;
            }
        }
        if (newFollower != null) {
            double gapBehind = subjectPos - newFollower.getPosition() - subject.getLength();
            if (gapBehind < newFollower.getS0()) {
                return false;
            }
        }
        return true;
    }

    /** Zipper merges only check ahead gap — follower will brake naturally via IDM after merge. */
    private boolean checkZipperGap(Vehicle subject, double subjectPos, Vehicle newLeader) {
        if (newLeader == null) {
            return true;
        }
        double gapAhead = newLeader.getPosition() - subjectPos - newLeader.getLength();
        return gapAhead >= subject.getLength();
    }

    /** Checks for obstacle conflicts in target lane (both ahead and behind subject position). */
    private boolean hasObstacleConflictInTarget(
            Vehicle subject, double subjectPos, Lane targetLane) {
        Obstacle targetObstacle = findNearestObstacleAhead(targetLane, subjectPos);
        if (targetObstacle != null) {
            double gapToObstacle =
                    targetObstacle.getPosition() - subjectPos - targetObstacle.getLength();
            double minObstacleGap =
                    subject.getS0()
                            + subject.getLength()
                            + subject.getSpeed() * subject.getTimeHeadway();
            if (gapToObstacle < minObstacleGap) {
                return true;
            }
        }
        for (Obstacle obs : targetLane.getObstaclesView()) {
            double gapBehindObs = subjectPos - obs.getPosition();
            if (gapBehindObs > 0 && gapBehindObs < subject.getLength() + subject.getS0()) {
                return true;
            }
        }
        return false;
    }

    /** Computes MOBIL incentive criterion and returns intent if threshold is exceeded. */
    private LaneChangeIntent evaluateIncentive(
            IncentiveContext ctx, AccelerationPair accel, TargetLaneContext tlCtx) {
        Vehicle oldFollower = ctx.currentLane.findFollowerAt(ctx.subjectPos);

        double aOldFollowerBefore =
                (oldFollower != null) ? computeIdmAccelWithLeader(oldFollower, ctx.subject) : 0.0;
        double aOldFollowerAfter =
                (oldFollower != null) ? computeIdmAccel(oldFollower, ctx.currentLeader) : 0.0;

        double aNewFollowerBefore =
                (tlCtx.newFollower() != null)
                        ? computeIdmAccel(tlCtx.newFollower(), tlCtx.newLeader())
                        : 0.0;
        double aNewFollowerAfter =
                (tlCtx.newFollower() != null)
                        ? computeIdmAccelWithLeader(tlCtx.newFollower(), ctx.subject)
                        : 0.0;

        double subjectGain = accel.target - accel.current;
        double neighborCost =
                aOldFollowerAfter - aOldFollowerBefore + (aNewFollowerAfter - aNewFollowerBefore);
        double incentive = subjectGain - POLITENESS * neighborCost;

        return incentive > tlCtx.aThreshold()
                ? new LaneChangeIntent(
                        ctx.subject, ctx.currentLane, tlCtx.targetLane(), ctx.subjectPos, incentive)
                : null;
    }

    /**
     * Compute IDM acceleration for vehicle in a lane, considering both vehicle leader and obstacles
     * (nearest ahead wins). This mirrors PhysicsEngine.tick() logic.
     */
    private double computeIdmAccelInLane(Vehicle vehicle, Vehicle vehicleLeader, Lane lane) {
        Obstacle nearestObstacle = findNearestObstacleAhead(lane, vehicle.getPosition());

        EffectiveLeader leader = resolveEffectiveLeader(vehicleLeader, nearestObstacle);
        if (leader == null) {
            return physicsEngine.computeAcceleration(vehicle, 0, 0, 0, false);
        }
        return physicsEngine.computeAcceleration(
                vehicle, leader.position(), leader.speed(), leader.length(), true);
    }

    /**
     * Resolves the effective leader from a vehicle leader and/or obstacle. Returns null if neither
     * is present (free-flow).
     */
    private EffectiveLeader resolveEffectiveLeader(
            Vehicle vehicleLeader, Obstacle nearestObstacle) {
        if (vehicleLeader != null && nearestObstacle != null) {
            if (vehicleLeader.getPosition() <= nearestObstacle.getPosition()) {
                return new EffectiveLeader(
                        vehicleLeader.getPosition(),
                        vehicleLeader.getSpeed(),
                        vehicleLeader.getLength());
            }
            return new EffectiveLeader(
                    nearestObstacle.getPosition(), 0.0, nearestObstacle.getLength());
        }
        if (vehicleLeader != null) {
            return new EffectiveLeader(
                    vehicleLeader.getPosition(),
                    vehicleLeader.getSpeed(),
                    vehicleLeader.getLength());
        }
        if (nearestObstacle != null) {
            return new EffectiveLeader(
                    nearestObstacle.getPosition(), 0.0, nearestObstacle.getLength());
        }
        return null;
    }

    /** Find nearest obstacle ahead of the given position in a lane. */
    Obstacle findNearestObstacleAhead(Lane lane, double position) {
        Obstacle nearest = null;
        double nearestPos = Double.MAX_VALUE;
        for (Obstacle obs : lane.getObstaclesView()) {
            if (obs.getPosition() > position && obs.getPosition() < nearestPos) {
                nearest = obs;
                nearestPos = obs.getPosition();
            }
        }
        return nearest;
    }

    /**
     * Compute IDM acceleration for vehicle following its leader (or free-flow if null). Used when
     * we don't need obstacle awareness (e.g., follower-with-specific-leader).
     */
    private double computeIdmAccel(Vehicle vehicle, Vehicle leader) {
        if (leader == null) {
            return physicsEngine.computeAcceleration(vehicle, 0, 0, 0, false);
        }
        return physicsEngine.computeAcceleration(
                vehicle, leader.getPosition(), leader.getSpeed(), leader.getLength(), true);
    }

    /** Compute IDM acceleration for follower with a specific vehicle as leader. */
    private double computeIdmAccelWithLeader(Vehicle follower, Vehicle leader) {
        return physicsEngine.computeAcceleration(
                follower, leader.getPosition(), leader.getSpeed(), leader.getLength(), true);
    }
}
