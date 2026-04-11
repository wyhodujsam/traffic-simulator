package com.trafficsimulator.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.trafficsimulator.engine.MOBILCalculator.LaneChangeIntent;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class LaneChangeEngine implements ILaneChangeEngine {

    private static final double COOLDOWN_SECONDS = 3.0; // seconds between lane changes
    private static final double BASE_DT = 0.05; // 50ms tick
    private static final int TRANSITION_TICKS = 10; // ticks for lane change animation
    private static final double LANE_CHANGE_COMPLETE = 1.0;

    private final MOBILCalculator mobilCalculator;
    private final ZipperMergeEngine zipperMergeEngine;

    public LaneChangeEngine(IPhysicsEngine physicsEngine) {
        this.mobilCalculator = new MOBILCalculator(physicsEngine);
        this.zipperMergeEngine = new ZipperMergeEngine();
    }

    /**
     * Main tick: evaluates MOBIL for all vehicles, resolves conflicts, commits moves. Called once
     * per tick AFTER physics, BEFORE despawn.
     */
    @Override
    public void tick(RoadNetwork network, long currentTick) {
        if (network == null) {
            return;
        }

        // Update lane change animation progress for vehicles mid-transition
        updateLaneChangeProgress(network);

        // Mark zipper merge candidates: first stopped vehicle behind each obstacle
        zipperMergeEngine.markZipperCandidates(network, currentTick);

        // Phase 1: Collect intents
        List<LaneChangeIntent> intents = collectIntents(network, currentTick);
        if (intents.isEmpty()) {
            return;
        }

        // Phase 2: Resolve conflicts
        List<LaneChangeIntent> resolved = resolveConflicts(intents);

        // Phase 3: Commit lane changes
        commitLaneChanges(resolved, currentTick);
    }

    /**
     * Phase 1: Iterate all vehicles, evaluate MOBIL for adjacent lanes, produce intents for
     * desirable lane changes.
     */
    private List<LaneChangeIntent> collectIntents(RoadNetwork network, long currentTick) {
        List<LaneChangeIntent> intents = new ArrayList<>();
        long cooldownTicks = (long) (COOLDOWN_SECONDS / BASE_DT);

        for (Road road : network.getRoads().values()) {
            collectIntentsForRoad(road, currentTick, cooldownTicks, intents);
        }
        return intents;
    }

    private void collectIntentsForRoad(
            Road road, long currentTick, long cooldownTicks, List<LaneChangeIntent> intents) {
        for (Lane lane : road.getLanes()) {
            if (!lane.isActive()) {
                continue;
            }
            for (Vehicle vehicle : lane.getVehiclesView()) {
                if (shouldSkipVehicle(vehicle, lane, currentTick, cooldownTicks)) {
                    continue;
                }
                LaneChangeIntent bestIntent = evaluateBestIntent(vehicle, lane, road);
                if (bestIntent != null) {
                    intents.add(bestIntent);
                }
            }
        }
    }

    /**
     * Returns true if the vehicle should be skipped for lane-change evaluation this tick. Handles
     * stuck-behind-obstacle check, cooldown check, and force/zipper bypass.
     */
    private boolean shouldSkipVehicle(
            Vehicle vehicle, Lane lane, long currentTick, long cooldownTicks) {
        return (zipperMergeEngine.isStuckBehindObstacle(vehicle, lane)
                        && !vehicle.isZipperCandidate())
                || (!vehicle.isForceLaneChange()
                        && !vehicle.isZipperCandidate()
                        && !vehicle.canChangeLane(currentTick, cooldownTicks));
    }

    /**
     * Evaluates both left and right neighbors for the given vehicle and returns the intent with the
     * highest incentive score, or null if neither is beneficial.
     */
    private LaneChangeIntent evaluateBestIntent(Vehicle vehicle, Lane lane, Road road) {
        LaneChangeIntent bestIntent =
                evaluateLaneIntent(
                        vehicle,
                        lane,
                        road.getLeftNeighbor(lane),
                        MOBILCalculator.A_THRESHOLD_LEFT);
        LaneChangeIntent rightIntent =
                evaluateLaneIntent(
                        vehicle,
                        lane,
                        road.getRightNeighbor(lane),
                        MOBILCalculator.A_THRESHOLD_RIGHT);

        if (rightIntent != null
                && (bestIntent == null
                        || rightIntent.incentiveScore() > bestIntent.incentiveScore())) {
            bestIntent = rightIntent;
        }
        return bestIntent;
    }

    private LaneChangeIntent evaluateLaneIntent(
            Vehicle vehicle, Lane currentLane, Lane targetLane, double threshold) {
        if (targetLane == null) {
            return null;
        }
        return mobilCalculator.evaluateMOBIL(vehicle, currentLane, targetLane, threshold);
    }

    /**
     * Phase 2: Group intents by target lane, resolve conflicts where two vehicles want overlapping
     * positions. Winner = highest incentive score.
     */
    private List<LaneChangeIntent> resolveConflicts(List<LaneChangeIntent> intents) {
        Map<String, List<LaneChangeIntent>> byTargetLane =
                intents.stream().collect(Collectors.groupingBy(i -> i.targetLane().getId()));

        List<LaneChangeIntent> resolved = new ArrayList<>();

        for (List<LaneChangeIntent> group : byTargetLane.values()) {
            resolveConflictsInGroup(group, resolved);
        }
        return resolved;
    }

    /**
     * Resolves conflicts within a single target-lane group sorted by position. Overlapping intents
     * are resolved by keeping the one with higher incentive.
     */
    private void resolveConflictsInGroup(
            List<LaneChangeIntent> group, List<LaneChangeIntent> resolved) {
        group.sort(Comparator.comparingDouble(LaneChangeIntent::position));

        LaneChangeIntent prev = null;
        for (LaneChangeIntent intent : group) {
            if (prev != null && isOverlapping(intent, prev)) {
                if (intent.incentiveScore() > prev.incentiveScore()) {
                    resolved.remove(prev);
                    resolved.add(intent);
                    prev = intent;
                }
                continue;
            }
            resolved.add(intent);
            prev = intent;
        }
    }

    /** Returns true if two intents overlap (gap less than minimum required). */
    private boolean isOverlapping(LaneChangeIntent intent, LaneChangeIntent prev) {
        double gap = intent.position() - prev.position();
        double minGap = intent.vehicle().getS0() + intent.vehicle().getLength();
        return gap < minGap;
    }

    /**
     * Phase 3: Apply resolved lane changes. Move vehicles between lanes, record cooldown timestamp,
     * initialize animation progress.
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
                zipperMergeEngine.recordZipperMerge(vehicle, source, currentTick);
                vehicle.setZipperCandidate(false);
            }

            log.debug(
                    "Lane change: vehicle={} from lane {} to lane {}",
                    vehicle.getId(),
                    source.getId(),
                    target.getId());
        }
    }

    /**
     * Advances laneChangeProgress for vehicles mid-transition. Progress goes from 0.0 to 1.0 over
     * TRANSITION_TICKS ticks.
     */
    private void updateLaneChangeProgress(RoadNetwork network) {
        double progressStep = 1.0 / TRANSITION_TICKS;
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                advanceLaneChangeInLane(lane, progressStep);
            }
        }
    }

    private void advanceLaneChangeInLane(Lane lane, double progressStep) {
        for (Vehicle v : lane.getVehiclesView()) {
            if (v.getLaneChangeProgress() >= LANE_CHANGE_COMPLETE
                    || v.getLaneChangeSourceIndex() < 0) {
                continue;
            }
            v.advanceLaneChangeProgress(progressStep);
            if (v.getLaneChangeProgress() >= LANE_CHANGE_COMPLETE) {
                v.completeLaneChange();
            }
        }
    }
}
