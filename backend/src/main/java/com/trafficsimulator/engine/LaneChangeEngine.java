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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LaneChangeEngine {

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

    private final PhysicsEngine physicsEngine;

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
    public void tick(RoadNetwork network, long currentTick) {
        if (network == null) return;

        // Update lane change animation progress for vehicles mid-transition
        updateLaneChangeProgress(network);

        // Mark zipper merge candidates: first stopped vehicle behind each obstacle
        markZipperCandidates(network);

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

                for (Vehicle vehicle : lane.getVehicles()) {
                    // Cooldown check (skip for forced and zipper candidates)
                    if (!vehicle.isForceLaneChange() && !vehicle.isZipperCandidate()) {
                        long ticksSince = currentTick - vehicle.getLastLaneChangeTick();
                        if (vehicle.getLastLaneChangeTick() > 0 && ticksSince < cooldownTicks) {
                            continue;
                        }
                    }

                    // Evaluate both directions, pick the best
                    LaneChangeIntent bestIntent = null;

                    // Try left (higher index)
                    Lane leftLane = road.getLeftNeighbor(lane);
                    if (leftLane != null) {
                        LaneChangeIntent intent = evaluateMOBIL(
                            vehicle, lane, leftLane, A_THRESHOLD_LEFT);
                        if (intent != null && (bestIntent == null
                            || intent.incentiveScore() > bestIntent.incentiveScore())) {
                            bestIntent = intent;
                        }
                    }

                    // Try right (lower index)
                    Lane rightLane = road.getRightNeighbor(lane);
                    if (rightLane != null) {
                        LaneChangeIntent intent = evaluateMOBIL(
                            vehicle, lane, rightLane, A_THRESHOLD_RIGHT);
                        if (intent != null && (bestIntent == null
                            || intent.incentiveScore() > bestIntent.incentiveScore())) {
                            bestIntent = intent;
                        }
                    }

                    if (bestIntent != null) {
                        intents.add(bestIntent);
                    }
                }
            }
        }
        return intents;
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

        // Find neighbors in target lane
        Vehicle newLeader = targetLane.findLeaderAt(subjectPos);
        Vehicle newFollower = targetLane.findFollowerAt(subjectPos);

        // Current acceleration of subject (obstacle-aware)
        Vehicle currentLeader = currentLane.getLeader(subject);
        double aSubjectCurrent = computeIdmAccelInLane(subject, currentLeader, currentLane);

        // Acceleration of subject in target lane (obstacle-aware)
        double aSubjectTarget = computeIdmAccelInLane(subject, newLeader, targetLane);

        // Safety criterion: new follower must not brake harder than b_safe
        // Zipper merge candidates get a relaxed threshold to allow cooperative merging
        double effectiveBSafe = subject.isZipperCandidate() ? B_SAFE_ZIPPER : B_SAFE;
        if (newFollower != null) {
            double aNewFollowerAfter = computeIdmAccelWithLeader(newFollower, subject);
            if (aNewFollowerAfter < -effectiveBSafe) {
                return null; // unsafe
            }
        }

        // Gap check: ensure minimum gap to vehicles in target lane
        if (newLeader != null) {
            double gapAhead = newLeader.getPosition() - subjectPos - newLeader.getLength();
            if (gapAhead < subject.getS0() + subject.getLength()) {
                return null; // not enough space ahead
            }
        }
        if (newFollower != null) {
            double gapBehind = subjectPos - newFollower.getPosition() - subject.getLength();
            if (gapBehind < newFollower.getS0()) {
                return null; // not enough space behind
            }
        }

        // Gap check: ensure no obstacle blocks the target position
        Obstacle targetObstacle = findNearestObstacleAhead(targetLane, subjectPos);
        if (targetObstacle != null) {
            double gapToObstacle = targetObstacle.getPosition() - subjectPos - targetObstacle.getLength();
            if (gapToObstacle < subject.getS0() + subject.getLength()) {
                return null; // obstacle too close in target lane
            }
        }

        // For forced lane changes (lane closed) or zipper merge, skip incentive check
        if (subject.isForceLaneChange() || subject.isZipperCandidate()) {
            double score = subject.isForceLaneChange() ? Double.MAX_VALUE : 100.0;
            return new LaneChangeIntent(subject, currentLane, targetLane,
                subjectPos, score);
        }

        // Incentive criterion:
        // a'_subject - a_subject > p * (a'_old_follower - a_old_follower
        //                              + a'_new_follower - a_new_follower) + a_threshold
        Vehicle oldFollower = currentLane.findFollowerAt(subjectPos);

        double aOldFollowerBefore = (oldFollower != null)
            ? computeIdmAccelWithLeader(oldFollower, subject) : 0.0;
        double aOldFollowerAfter = (oldFollower != null)
            ? computeIdmAccel(oldFollower, currentLeader) : 0.0;
            // After subject leaves, old follower's leader becomes subject's old leader

        double aNewFollowerBefore = (newFollower != null)
            ? computeIdmAccel(newFollower, newLeader) : 0.0;
        double aNewFollowerAfter = (newFollower != null)
            ? computeIdmAccelWithLeader(newFollower, subject) : 0.0;

        double subjectGain = aSubjectTarget - aSubjectCurrent;
        double neighborCost = (aOldFollowerAfter - aOldFollowerBefore)
                            + (aNewFollowerAfter - aNewFollowerBefore);
        double incentive = subjectGain - POLITENESS * neighborCost;

        if (incentive > aThreshold) {
            return new LaneChangeIntent(subject, currentLane, targetLane,
                subjectPos, incentive);
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
        for (Obstacle obs : lane.getObstacles()) {
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
            source.getVehicles().remove(vehicle);
            target.getVehicles().add(vehicle);
            vehicle.setLane(target);

            // Record cooldown
            vehicle.setLastLaneChangeTick(currentTick);

            // Clear force flag if was forced
            vehicle.setForceLaneChange(false);

            // Initialize animation: track source lane index, progress = 0
            vehicle.setLaneChangeSourceIndex(source.getLaneIndex());
            vehicle.setLaneChangeProgress(0.0);

            log.debug("Lane change: vehicle={} from lane {} to lane {}",
                vehicle.getId(), source.getId(), target.getId());
        }
    }

    /**
     * Marks the first stopped/slow vehicle behind each obstacle as a zipper merge candidate.
     * Only one vehicle per obstacle gets zipper status → 1-by-1 merge.
     */
    private void markZipperCandidates(RoadNetwork network) {
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                // Clear previous marks
                for (Vehicle v : lane.getVehicles()) {
                    v.setZipperCandidate(false);
                }

                // For each obstacle, find the first vehicle behind it that is slow/stopped
                for (Obstacle obs : lane.getObstacles()) {
                    Vehicle closest = null;
                    double closestDist = Double.MAX_VALUE;
                    for (Vehicle v : lane.getVehicles()) {
                        double dist = obs.getPosition() - v.getPosition();
                        if (dist > 0 && dist < OBSTACLE_PROXIMITY && dist < closestDist) {
                            closest = v;
                            closestDist = dist;
                        }
                    }
                    if (closest != null && closest.getSpeed() < 2.0) {
                        closest.setZipperCandidate(true);
                    }
                }
            }
        }
    }

    /**
     * Advances laneChangeProgress for vehicles mid-transition.
     * Progress goes from 0.0 to 1.0 over TRANSITION_TICKS ticks.
     */
    private void updateLaneChangeProgress(RoadNetwork network) {
        double progressStep = 1.0 / TRANSITION_TICKS;
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                for (Vehicle v : lane.getVehicles()) {
                    if (v.getLaneChangeProgress() < 1.0 && v.getLaneChangeSourceIndex() >= 0) {
                        v.setLaneChangeProgress(
                            Math.min(1.0, v.getLaneChangeProgress() + progressStep));
                        if (v.getLaneChangeProgress() >= 1.0) {
                            v.setLaneChangeSourceIndex(-1); // animation done
                        }
                    }
                }
            }
        }
    }
}
