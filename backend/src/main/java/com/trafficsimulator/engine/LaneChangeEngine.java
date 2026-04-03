package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LaneChangeEngine implements ILaneChangeEngine {

    // MOBIL parameters
    private static final double B_SAFE = 4.0;          // safe braking limit m/s^2
    private static final double B_SAFE_ZIPPER = 5.5;   // relaxed braking limit for zipper merge
    private static final double POLITENESS = 0.3;       // weight for neighbors' disadvantage
    private static final double A_THRESHOLD_LEFT = 0.3; // threshold for moving left (overtake)
    private static final double A_THRESHOLD_RIGHT = 0.1;// threshold for moving right (keep-right)
    private static final double COOLDOWN_SECONDS = 3.0; // seconds between lane changes
    private static final double BASE_DT = 0.05;         // 50ms tick
    private static final int    TRANSITION_TICKS = 10;   // ticks for lane change animation
    private static final double OBSTACLE_PROXIMITY = 30.0; // metres — "stuck behind obstacle" threshold
    private static final int    ZIPPER_INTERVAL_TICKS = 40; // ticks between zipper merges per obstacle (~2s)

    private final IPhysicsEngine physicsEngine;

    /** Tracks last zipper merge tick per obstacle ID to enforce merge interval */
    private final Map<String, Long> lastZipperMergeTick = new HashMap<>();

    /** Record for a lane-change intent before conflict resolution. */
    record LaneChangeIntent(
        Vehicle vehicle,
        Lane sourceLane,
        Lane targetLane,
        double position,
        double incentiveScore
    ) {}

    /**
     * Main tick: evaluates MOBIL for all vehicles, resolves conflicts, commits moves.
     * Called once per tick AFTER physics, BEFORE despawn.
     */
    @Override
    public void tick(RoadNetwork network, long currentTick) {
        if (network == null) return;

        // Update lane change animation progress for vehicles mid-transition
        updateLaneChangeProgress(network);

        // Mark zipper merge candidates: first stopped vehicle behind each obstacle
        markZipperCandidates(network, currentTick);

        // Phase 1: Collect intents
        List<LaneChangeIntent> intents = collectIntents(network, currentTick);
        if (intents.isEmpty()) return;

        // Phase 2: Resolve conflicts
        List<LaneChangeIntent> resolved = resolveConflicts(intents);

        // Phase 3: Commit lane changes
        commitLaneChanges(resolved, currentTick);
    }

    /**
     * Phase 1: Iterate all vehicles, evaluate MOBIL for adjacent lanes,
     * produce intents for desirable lane changes.
     */
    private List<LaneChangeIntent> collectIntents(RoadNetwork network, long currentTick) {
        List<LaneChangeIntent> intents = new ArrayList<>();
        long cooldownTicks = (long)(COOLDOWN_SECONDS / BASE_DT); // 60 ticks

        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                if (!lane.isActive()) continue;
                for (Vehicle vehicle : lane.getVehiclesView()) {
                    if (shouldSkipVehicle(vehicle, lane, currentTick, cooldownTicks)) continue;
                    LaneChangeIntent bestIntent = evaluateBestIntent(vehicle, lane, road);
                    if (bestIntent != null) {
                        intents.add(bestIntent);
                    }
                }
            }
        }
        return intents;
    }

    /**
     * Returns true if the vehicle should be skipped for lane-change evaluation this tick.
     * Handles stuck-behind-obstacle check, cooldown check, and force/zipper bypass.
     */
    private boolean shouldSkipVehicle(Vehicle vehicle, Lane lane, long currentTick, long cooldownTicks) {
        if (isStuckBehindObstacle(vehicle, lane) && !vehicle.isZipperCandidate()) {
            return true;
        }
        if (!vehicle.isForceLaneChange() && !vehicle.isZipperCandidate()) {
            if (!vehicle.canChangeLane(currentTick, cooldownTicks)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates both left and right neighbors for the given vehicle and returns
     * the intent with the highest incentive score, or null if neither is beneficial.
     */
    private LaneChangeIntent evaluateBestIntent(Vehicle vehicle, Lane lane, Road road) {
        LaneChangeIntent bestIntent = null;

        Lane leftLane = road.getLeftNeighbor(lane);
        if (leftLane != null) {
            LaneChangeIntent intent = evaluateMOBIL(vehicle, lane, leftLane, A_THRESHOLD_LEFT);
            if (intent != null && (bestIntent == null
                    || intent.incentiveScore() > bestIntent.incentiveScore())) {
                bestIntent = intent;
            }
        }

        Lane rightLane = road.getRightNeighbor(lane);
        if (rightLane != null) {
            LaneChangeIntent intent = evaluateMOBIL(vehicle, lane, rightLane, A_THRESHOLD_RIGHT);
            if (intent != null && (bestIntent == null
                    || intent.incentiveScore() > bestIntent.incentiveScore())) {
                bestIntent = intent;
            }
        }

        return bestIntent;
    }

    /**
     * Evaluates MOBIL safety + incentive criteria for one vehicle moving to targetLane.
     * Returns an intent if the move is both safe and beneficial, null otherwise.
     *
     * For forced lane changes (closed lane), only safety criterion is checked.
     */
    private LaneChangeIntent evaluateMOBIL(Vehicle subject, Lane currentLane,
                                            Lane targetLane, double aThreshold) {
        double subjectPos = subject.getPosition();

        Vehicle newLeader = targetLane.findLeaderAt(subjectPos);
        Vehicle newFollower = targetLane.findFollowerAt(subjectPos);
        Vehicle currentLeader = currentLane.getLeader(subject);

        double aSubjectCurrent = computeIdmAccelInLane(subject, currentLeader, currentLane);
        double aSubjectTarget = computeIdmAccelInLane(subject, newLeader, targetLane);

        boolean isZipper = subject.isZipperCandidate();
        if (!isSafeLaneChange(subject, newFollower, isZipper)) return null;
        if (!checkGapSafety(subject, subjectPos, newLeader, newFollower, isZipper)) return null;

        if (hasObstacleConflictInTarget(subject, subjectPos, targetLane)) return null;

        if (subject.isForceLaneChange() || subject.isZipperCandidate()) {
            double score = subject.isForceLaneChange() ? Double.MAX_VALUE : 100.0;
            return new LaneChangeIntent(subject, currentLane, targetLane, subjectPos, score);
        }

        return evaluateIncentive(subject, currentLane, currentLeader, subjectPos,
                newLeader, newFollower, aSubjectCurrent, aSubjectTarget, aThreshold,
                targetLane);
    }

    /**
     * Safety criterion: new follower must not brake harder than b_safe.
     * Zipper candidates skip this check (follower will brake naturally via IDM).
     */
    private boolean isSafeLaneChange(Vehicle subject, Vehicle newFollower, boolean isZipper) {
        if (newFollower == null || isZipper) return true;
        double aNewFollowerAfter = computeIdmAccelWithLeader(newFollower, subject);
        return aNewFollowerAfter >= -B_SAFE;
    }

    /**
     * Gap check: ensures minimum gap to vehicles in target lane.
     * Zipper merges only check ahead gap (follower will brake via IDM after merge).
     */
    private boolean checkGapSafety(Vehicle subject, double subjectPos,
                                    Vehicle newLeader, Vehicle newFollower, boolean isZipper) {
        if (isZipper) {
            if (newLeader != null) {
                double gapAhead = newLeader.getPosition() - subjectPos - newLeader.getLength();
                if (gapAhead < subject.getLength()) return false;
            }
            return true;
        }

        if (newLeader != null) {
            double gapAhead = newLeader.getPosition() - subjectPos - newLeader.getLength();
            if (gapAhead < subject.getS0() + subject.getLength()) return false;
        }
        if (newFollower != null) {
            double gapBehind = subjectPos - newFollower.getPosition() - subject.getLength();
            if (gapBehind < newFollower.getS0()) return false;
        }
        return true;
    }

    /**
     * Checks for obstacle conflicts in target lane (both ahead and behind subject position).
     */
    private boolean hasObstacleConflictInTarget(Vehicle subject, double subjectPos, Lane targetLane) {
        Obstacle targetObstacle = findNearestObstacleAhead(targetLane, subjectPos);
        if (targetObstacle != null) {
            double gapToObstacle = targetObstacle.getPosition() - subjectPos - targetObstacle.getLength();
            double minObstacleGap = subject.getS0() + subject.getLength()
                + subject.getSpeed() * subject.getT();
            if (gapToObstacle < minObstacleGap) return true;
        }
        for (Obstacle obs : targetLane.getObstaclesView()) {
            double gapBehindObs = subjectPos - obs.getPosition();
            if (gapBehindObs > 0 && gapBehindObs < subject.getLength() + subject.getS0()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes MOBIL incentive criterion and returns intent if threshold is exceeded.
     */
    private LaneChangeIntent evaluateIncentive(Vehicle subject, Lane currentLane, Vehicle currentLeader,
                                                double subjectPos, Vehicle newLeader, Vehicle newFollower,
                                                double aSubjectCurrent, double aSubjectTarget,
                                                double aThreshold, Lane targetLane) {
        Vehicle oldFollower = currentLane.findFollowerAt(subjectPos);

        double aOldFollowerBefore = (oldFollower != null)
            ? computeIdmAccelWithLeader(oldFollower, subject) : 0.0;
        double aOldFollowerAfter = (oldFollower != null)
            ? computeIdmAccel(oldFollower, currentLeader) : 0.0;

        double aNewFollowerBefore = (newFollower != null)
            ? computeIdmAccel(newFollower, newLeader) : 0.0;
        double aNewFollowerAfter = (newFollower != null)
            ? computeIdmAccelWithLeader(newFollower, subject) : 0.0;

        double subjectGain = aSubjectTarget - aSubjectCurrent;
        double neighborCost = (aOldFollowerAfter - aOldFollowerBefore)
                            + (aNewFollowerAfter - aNewFollowerBefore);
        double incentive = subjectGain - POLITENESS * neighborCost;

        if (incentive > aThreshold) {
            return new LaneChangeIntent(subject, currentLane, targetLane, subjectPos, incentive);
        }
        return null;
    }

    /**
     * Compute IDM acceleration for vehicle in a lane, considering both vehicle leader
     * and obstacles (nearest ahead wins). This mirrors PhysicsEngine.tick() logic.
     */
    private double computeIdmAccelInLane(Vehicle vehicle, Vehicle vehicleLeader, Lane lane) {
        // Find nearest obstacle ahead in the lane
        Obstacle nearestObstacle = findNearestObstacleAhead(lane, vehicle.getPosition());

        double leaderPos, leaderSpeed, leaderLength;
        boolean hasLeader;

        if (vehicleLeader != null && nearestObstacle != null) {
            if (vehicleLeader.getPosition() <= nearestObstacle.getPosition()) {
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
            leaderPos = 0; leaderSpeed = 0; leaderLength = 0;
            hasLeader = false;
        }

        return physicsEngine.computeAcceleration(vehicle, leaderPos, leaderSpeed, leaderLength, hasLeader);
    }

    /**
     * Find nearest obstacle ahead of the given position in a lane.
     */
    private Obstacle findNearestObstacleAhead(Lane lane, double position) {
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
     * Compute IDM acceleration for vehicle following its leader (or free-flow if null).
     * Used when we don't need obstacle awareness (e.g., follower-with-specific-leader).
     */
    private double computeIdmAccel(Vehicle vehicle, Vehicle leader) {
        if (leader == null) {
            return physicsEngine.computeAcceleration(vehicle, 0, 0, 0, false);
        }
        return physicsEngine.computeAcceleration(vehicle,
            leader.getPosition(), leader.getSpeed(), leader.getLength(), true);
    }

    /**
     * Compute IDM acceleration for follower with a specific vehicle as leader.
     */
    private double computeIdmAccelWithLeader(Vehicle follower, Vehicle leader) {
        return physicsEngine.computeAcceleration(follower,
            leader.getPosition(), leader.getSpeed(), leader.getLength(), true);
    }

    /**
     * Phase 2: Group intents by target lane, resolve conflicts where two vehicles
     * want overlapping positions. Winner = highest incentive score.
     */
    private List<LaneChangeIntent> resolveConflicts(List<LaneChangeIntent> intents) {
        // Group by target lane ID
        Map<String, List<LaneChangeIntent>> byTargetLane = intents.stream()
            .collect(Collectors.groupingBy(i -> i.targetLane().getId()));

        List<LaneChangeIntent> resolved = new ArrayList<>();

        for (List<LaneChangeIntent> group : byTargetLane.values()) {
            // Sort by position ascending
            group.sort(Comparator.comparingDouble(LaneChangeIntent::position));

            // Check for overlapping intents
            LaneChangeIntent prev = null;
            for (LaneChangeIntent intent : group) {
                if (prev != null) {
                    double gap = intent.position() - prev.position();
                    double minGap = intent.vehicle().getS0() + intent.vehicle().getLength();
                    if (gap < minGap) {
                        // Conflict: keep the one with higher incentive
                        if (intent.incentiveScore() > prev.incentiveScore()) {
                            resolved.remove(prev);
                            resolved.add(intent);
                            prev = intent;
                        }
                        // else keep prev, skip current intent
                        continue;
                    }
                }
                resolved.add(intent);
                prev = intent;
            }
        }
        return resolved;
    }

    /**
     * Phase 3: Apply resolved lane changes. Move vehicles between lanes,
     * record cooldown timestamp, initialize animation progress.
     */
    private void commitLaneChanges(List<LaneChangeIntent> intents, long currentTick) {
        for (LaneChangeIntent intent : intents) {
            Vehicle vehicle = intent.vehicle();
            Lane source = intent.sourceLane();
            Lane target = intent.targetLane();

            // Move vehicle from source to target lane
            source.removeVehicle(vehicle);
            target.addVehicle(vehicle);

            // Domain method: sets lane, animation tracking, and cooldown
            vehicle.startLaneChange(target, source.getLaneIndex(), currentTick);

            // Clear force flag if was forced
            vehicle.setForceLaneChange(false);

            // Record zipper merge time for rate-limiting
            if (vehicle.isZipperCandidate()) {
                recordZipperMerge(vehicle, source, currentTick);
                vehicle.setZipperCandidate(false);
            }

            log.debug("Lane change: vehicle={} from lane {} to lane {}",
                vehicle.getId(), source.getId(), target.getId());
        }
    }

    /**
     * Records the tick of a zipper merge for the obstacle this vehicle was queued behind.
     */
    private void recordZipperMerge(Vehicle vehicle, Lane source, long currentTick) {
        for (Obstacle obs : source.getObstaclesView()) {
            double dist = obs.getPosition() - vehicle.getPosition();
            if (dist > -5 && dist < OBSTACLE_PROXIMITY) {
                lastZipperMergeTick.put(obs.getId(), currentTick);
                break;
            }
        }
    }

    /**
     * Returns true if the vehicle is slow/stopped and within OBSTACLE_PROXIMITY of an obstacle ahead.
     */
    private boolean isStuckBehindObstacle(Vehicle vehicle, Lane lane) {
        if (vehicle.getSpeed() > 2.0) return false;
        for (Obstacle obs : lane.getObstaclesView()) {
            double dist = obs.getPosition() - vehicle.getPosition();
            if (dist > 0 && dist < OBSTACLE_PROXIMITY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks the first stopped/slow vehicle behind each obstacle as a zipper merge candidate.
     * Only one vehicle per obstacle gets zipper status → 1-by-1 merge.
     * Enforces ZIPPER_INTERVAL_TICKS between merges per obstacle.
     */
    private void markZipperCandidates(RoadNetwork network, long currentTick) {
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                clearZipperMarks(lane);
                markZipperCandidatesInLane(lane, currentTick);
            }
        }
    }

    /**
     * Clears zipper candidate marks from all vehicles in the lane.
     */
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

    /**
     * Finds the closest vehicle behind the given obstacle within OBSTACLE_PROXIMITY.
     */
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

    /**
     * Advances laneChangeProgress for vehicles mid-transition.
     * Progress goes from 0.0 to 1.0 over TRANSITION_TICKS ticks.
     */
    private void updateLaneChangeProgress(RoadNetwork network) {
        double progressStep = 1.0 / TRANSITION_TICKS;
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                for (Vehicle v : lane.getVehiclesView()) {
                    if (v.getLaneChangeProgress() < 1.0 && v.getLaneChangeSourceIndex() >= 0) {
                        v.advanceLaneChangeProgress(progressStep);
                        if (v.getLaneChangeProgress() >= 1.0) {
                            v.completeLaneChange();
                        }
                    }
                }
            }
        }
    }
}
