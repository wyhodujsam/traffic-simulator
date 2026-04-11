package com.trafficsimulator.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class IntersectionManager implements IIntersectionManager {

    private static final double MIN_ENTRY_GAP = 7.0; // vehicle length + s0
    private static final int DEADLOCK_THRESHOLD_TICKS = 200; // 200 ticks = 10s at 20Hz

    private final RoundaboutManager roundaboutManager;
    private final Map<String, IntersectionState> intersectionStates = new HashMap<>();

    private static final class IntersectionState {
        long lastTransferTick;
        int waitingVehicleCount;
    }

    record TransferContext(
            Intersection ixtn,
            String inboundRoadId,
            Road inboundRoad,
            Lane inLane,
            RoadNetwork network,
            Map<String, Double> lastPlacedPosition) {}

    public IntersectionManager() {
        this.roundaboutManager = new RoundaboutManager();
    }

    /**
     * Builds a map of laneId -> stopLinePosition for all lanes that should have a red-light or
     * box-blocking stop. Called each tick before physics.
     */
    @Override
    public Map<String, Double> computeStopLines(RoadNetwork network) {
        Map<String, Double> stopLines = new HashMap<>();
        for (Intersection ixtn : network.getIntersections().values()) {
            computeStopLinesForIntersection(ixtn, network, stopLines);
        }
        return stopLines;
    }

    private void computeStopLinesForIntersection(
            Intersection ixtn, RoadNetwork network, Map<String, Double> stopLines) {
        for (String inboundRoadId : ixtn.getInboundRoadIds()) {
            Road inboundRoad = network.getRoads().get(inboundRoadId);
            if (inboundRoad == null) {
                continue;
            }
            if (!canEnterIntersection(ixtn, inboundRoadId, network)) {
                addStopLinesForRoad(ixtn, inboundRoad, stopLines);
            }
        }
    }

    private void addStopLinesForRoad(
            Intersection ixtn, Road inboundRoad, Map<String, Double> stopLines) {
        double buffer = IntersectionGeometry.computeStopLineBuffer(ixtn, inboundRoad);
        double stopPos = Math.max(0, inboundRoad.getLength() - buffer);
        for (Lane lane : inboundRoad.getLanes()) {
            stopLines.put(lane.getId(), stopPos);
        }
    }

    /**
     * Checks if a vehicle on the given inbound road can enter the intersection. Considers: traffic
     * light state, right-of-way priority, roundabout yield, and box-blocking.
     */
    private boolean canEnterIntersection(
            Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        if (ixtn.getType() == IntersectionType.SIGNAL
                && ixtn.getTrafficLight() != null
                && !ixtn.getTrafficLight().isGreen(inboundRoadId)) {
            return false;
        }

        if ((ixtn.getType() == IntersectionType.PRIORITY || ixtn.getType() == IntersectionType.NONE)
                && hasVehicleFromRight(ixtn, inboundRoadId, network)) {
            return false;
        }

        if (ixtn.getType() == IntersectionType.ROUNDABOUT
                && roundaboutManager.isRoundaboutEntryBlocked(ixtn, inboundRoadId, network)) {
            return false;
        }

        return checkBoxBlocking(ixtn, inboundRoadId, network);
    }

    /**
     * Box-blocking check: returns true if at least one outbound road has space at clip edge entry.
     */
    private boolean checkBoxBlocking(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        return ixtn.getOutboundRoadIds().stream()
                .anyMatch(
                        outRoadId ->
                                hasSpaceOnOutboundRoad(ixtn, inboundRoadId, outRoadId, network));
    }

    private boolean hasSpaceOnOutboundRoad(
            Intersection ixtn, String inboundRoadId, String outRoadId, RoadNetwork network) {
        if (outRoadId.equals(IntersectionGeometry.reverseRoadId(inboundRoadId))) {
            return false; // skip U-turn
        }
        Road outRoad = network.getRoads().get(outRoadId);
        if (outRoad == null) {
            return false;
        }
        double outBuffer = IntersectionGeometry.computeStopLineBuffer(ixtn, outRoad);
        return hasSpaceOnAnyActiveLane(outRoad, outBuffer);
    }

    private boolean hasSpaceOnAnyActiveLane(Road road, double outBuffer) {
        return road.getLanes().stream()
                .filter(Lane::isActive)
                .anyMatch(lane -> isLaneSpaceFree(lane, outBuffer));
    }

    private boolean isLaneSpaceFree(Lane lane, double outBuffer) {
        Vehicle first = lane.findLeaderAt(outBuffer - 1.0);
        return first == null || first.getPosition() >= outBuffer + MIN_ENTRY_GAP;
    }

    /**
     * Returns true if any vehicle is approaching the intersection from the right of the given
     * inbound road. Uses geometric angle calculation.
     */
    private boolean hasVehicleFromRight(
            Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        Road myRoad = network.getRoads().get(inboundRoadId);
        if (myRoad == null) {
            return false;
        }

        double myAngle =
                Math.atan2(
                        myRoad.getEndY() - myRoad.getStartY(),
                        myRoad.getEndX() - myRoad.getStartX());

        return ixtn.getInboundRoadIds().stream()
                .anyMatch(
                        otherRoadId ->
                                isApproachingFromRight(
                                        otherRoadId, inboundRoadId, myAngle, network));
    }

    private boolean isApproachingFromRight(
            String otherRoadId, String inboundRoadId, double myAngle, RoadNetwork network) {
        if (otherRoadId.equals(inboundRoadId)) {
            return false;
        }
        Road otherRoad = network.getRoads().get(otherRoadId);
        if (otherRoad == null) {
            return false;
        }
        double threshold =
                otherRoad.getLength() - IntersectionGeometry.STOP_LINE_BUFFER_DEFAULT - 10.0;
        if (!hasWaitingVehicleOnRoad(otherRoad, threshold)) {
            return false;
        }
        double otherAngle =
                Math.atan2(
                        otherRoad.getEndY() - otherRoad.getStartY(),
                        otherRoad.getEndX() - otherRoad.getStartX());
        return IntersectionGeometry.isApproachFromRight(myAngle, otherAngle);
    }

    /** Returns true if any vehicle on the given road is at or past the threshold position. */
    private boolean hasWaitingVehicleOnRoad(Road road, double threshold) {
        return road.getLanes().stream()
                .flatMap(lane -> lane.getVehiclesView().stream())
                .anyMatch(v -> v.getPosition() >= threshold);
    }

    /**
     * Transfers vehicles that have reached the end of inbound roads through intersections to
     * outbound roads. Called each tick after physics, before despawn.
     */
    @Override
    public void processTransfers(RoadNetwork network, long currentTick) {
        for (Intersection ixtn : network.getIntersections().values()) {
            processTransfersForIntersection(ixtn, network, currentTick);
        }

        checkDeadlocks(network, currentTick);
    }

    private void processTransfersForIntersection(
            Intersection ixtn, RoadNetwork network, long currentTick) {
        Map<String, Double> lastPlaced = new HashMap<>();

        int waitingCount = countWaitingVehicles(ixtn, network);
        boolean transferredAny = transferFromInboundRoads(ixtn, network, lastPlaced);

        IntersectionState state =
                intersectionStates.computeIfAbsent(ixtn.getId(), k -> new IntersectionState());
        state.waitingVehicleCount = waitingCount;
        if (transferredAny) {
            state.lastTransferTick = currentTick;
        }
    }

    /** Counts vehicles waiting at stop lines across all inbound roads of the intersection. */
    private int countWaitingVehicles(Intersection ixtn, RoadNetwork network) {
        int waitingCount = 0;
        for (String inboundRoadId : ixtn.getInboundRoadIds()) {
            waitingCount += countWaitingVehiclesOnRoad(ixtn, inboundRoadId, network);
        }
        return waitingCount;
    }

    private int countWaitingVehiclesOnRoad(
            Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        Road inboundRoad = network.getRoads().get(inboundRoadId);
        if (inboundRoad == null) {
            return 0;
        }
        double buffer = IntersectionGeometry.computeStopLineBuffer(ixtn, inboundRoad);
        double waitThreshold = inboundRoad.getLength() - buffer - 5.0;
        return (int)
                inboundRoad.getLanes().stream()
                        .flatMap(lane -> lane.getVehiclesView().stream())
                        .filter(v -> v.getPosition() >= waitThreshold && v.getSpeed() < 0.5)
                        .count();
    }

    /**
     * Iterates inbound roads, checks canEnterIntersection, and transfers vehicles per lane. Returns
     * true if any transfer occurred.
     */
    private boolean transferFromInboundRoads(
            Intersection ixtn, RoadNetwork network, Map<String, Double> lastPlaced) {
        boolean transferredAny = false;
        for (String inboundRoadId : ixtn.getInboundRoadIds()) {
            if (transferFromInboundRoad(ixtn, inboundRoadId, network, lastPlaced)) {
                transferredAny = true;
            }
        }
        return transferredAny;
    }

    private boolean transferFromInboundRoad(
            Intersection ixtn,
            String inboundRoadId,
            RoadNetwork network,
            Map<String, Double> lastPlaced) {
        Road inboundRoad = network.getRoads().get(inboundRoadId);
        if (inboundRoad == null || !canEnterIntersection(ixtn, inboundRoadId, network)) {
            return false;
        }
        boolean transferred = false;
        for (Lane inLane : inboundRoad.getLanes()) {
            if (transferVehiclesFromLane(ixtn, inboundRoadId, inLane, network, lastPlaced)) {
                transferred = true;
            }
        }
        return transferred;
    }

    /**
     * Detects deadlocked intersections (2+ approaches blocked with no transfer for
     * DEADLOCK_THRESHOLD_TICKS) and force-advances the longest-waiting vehicle.
     */
    private void checkDeadlocks(RoadNetwork network, long currentTick) {
        for (Intersection ixtn : network.getIntersections().values()) {
            checkDeadlockAtIntersection(ixtn, network, currentTick);
        }
    }

    private void checkDeadlockAtIntersection(
            Intersection ixtn, RoadNetwork network, long currentTick) {
        IntersectionState state =
                intersectionStates.computeIfAbsent(ixtn.getId(), k -> new IntersectionState());
        if (state.waitingVehicleCount < 2) {
            return;
        }
        long ticksSinceTransfer = currentTick - state.lastTransferTick;
        if (ticksSinceTransfer < DEADLOCK_THRESHOLD_TICKS) {
            return;
        }
        resolveDeadlock(ixtn, network, state, currentTick, ticksSinceTransfer);
    }

    /** Resolves a detected deadlock by force-advancing the longest-waiting vehicle. */
    private void resolveDeadlock(
            Intersection ixtn,
            RoadNetwork network,
            IntersectionState state,
            long currentTick,
            long ticksSinceTransfer) {
        Vehicle victim = findLongestWaitingVehicle(ixtn, network);
        if (victim == null) {
            return;
        }

        forceTransferVehicle(victim, ixtn, network);
        state.lastTransferTick = currentTick;

        log.warn(
                "Deadlock resolved at intersection {} by force-advancing vehicle {} (waited {} ticks)",
                ixtn.getId(),
                victim.getId(),
                ticksSinceTransfer);
    }

    private Vehicle findLongestWaitingVehicle(Intersection ixtn, RoadNetwork network) {
        Vehicle oldest = null;
        for (String inRoadId : ixtn.getInboundRoadIds()) {
            Vehicle candidate = findOldestStoppedVehicleOnRoad(inRoadId, ixtn, network);
            if (candidate != null
                    && (oldest == null || candidate.getSpawnedAt() < oldest.getSpawnedAt())) {
                oldest = candidate;
            }
        }
        return oldest;
    }

    private Vehicle findOldestStoppedVehicleOnRoad(
            String inRoadId, Intersection ixtn, RoadNetwork network) {
        Road inRoad = network.getRoads().get(inRoadId);
        if (inRoad == null) {
            return null;
        }
        double buffer = IntersectionGeometry.computeStopLineBuffer(ixtn, inRoad);
        double threshold = inRoad.getLength() - buffer - 5.0;
        return inRoad.getLanes().stream()
                .flatMap(lane -> lane.getVehiclesView().stream())
                .filter(v -> v.getPosition() >= threshold && v.getSpeed() < 0.5)
                .min(Comparator.comparingLong(Vehicle::getSpawnedAt))
                .orElse(null);
    }

    private void forceTransferVehicle(Vehicle victim, Intersection ixtn, RoadNetwork network) {
        // Find victim's current lane and remove
        Lane victimLane = victim.getLane();
        if (victimLane == null) {
            return;
        }
        victimLane.removeVehicle(victim);

        // Pick any outbound road
        if (ixtn.getOutboundRoadIds().isEmpty()) {
            return;
        }
        String outRoadId =
                ixtn.getOutboundRoadIds()
                        .get(ThreadLocalRandom.current().nextInt(ixtn.getOutboundRoadIds().size()));
        Road outRoad = network.getRoads().get(outRoadId);
        if (outRoad == null) {
            return;
        }

        Lane targetLane = pickTargetLane(outRoad);
        if (targetLane == null) {
            return;
        }

        // Force transfer -- ignores space check; start at clip edge
        double outBuffer = IntersectionGeometry.computeStopLineBuffer(ixtn, outRoad);
        victim.updatePhysics(outBuffer, victim.getSpeed(), victim.getAcceleration());
        victim.setLane(targetLane); // transfer = special case, not a lane change
        victim.completeLaneChange();
        targetLane.addVehicle(victim);
    }

    /**
     * Transfers vehicles from a lane through an intersection. Returns true if at least one vehicle
     * was transferred.
     *
     * @param lastPlacedPosition shared map (laneId -> last placed position) across all
     *     transferVehiclesFromLane calls within one processTransfers tick. Prevents vehicles from
     *     different inbound roads from being placed at the same position on the same target lane.
     */
    private boolean transferVehiclesFromLane(
            Intersection ixtn,
            String inboundRoadId,
            Lane inLane,
            RoadNetwork network,
            Map<String, Double> lastPlacedPosition) {
        Road inboundRoad = network.getRoads().get(inboundRoadId);
        double buffer =
                inboundRoad != null
                        ? IntersectionGeometry.computeStopLineBuffer(ixtn, inboundRoad)
                        : IntersectionGeometry.STOP_LINE_BUFFER_DEFAULT;
        double threshold = inLane.getLength() - buffer;
        var ctx =
                new TransferContext(
                        ixtn, inboundRoadId, inboundRoad, inLane, network, lastPlacedPosition);
        List<Vehicle> toTransfer = collectTransferableVehicles(ctx, threshold);

        for (Vehicle v : toTransfer) {
            inLane.removeVehicle(v);
        }
        return !toTransfer.isEmpty();
    }

    private List<Vehicle> collectTransferableVehicles(TransferContext ctx, double threshold) {
        List<Vehicle> toTransfer = new ArrayList<>();
        for (Vehicle v : ctx.inLane().getVehiclesView()) {
            if (v.getPosition() >= threshold && tryTransferVehicle(v, ctx)) {
                toTransfer.add(v);
            }
        }
        return toTransfer;
    }

    /**
     * Attempts to transfer a single vehicle through the intersection to an outbound road. Returns
     * true if the transfer succeeded.
     */
    private boolean tryTransferVehicle(Vehicle v, TransferContext ctx) {
        String outRoadId = pickOutboundRoad(ctx.ixtn(), ctx.inboundRoadId(), ctx.network());
        if (outRoadId == null) {
            return false;
        }

        Road outRoad = ctx.network().getRoads().get(outRoadId);
        if (outRoad == null) {
            return false;
        }

        Lane targetLane = pickTargetLane(outRoad, ctx.inboundRoad());
        return targetLane != null
                && placeVehicleOnTargetLane(
                        v, ctx.ixtn(), outRoad, targetLane, ctx.lastPlacedPosition());
    }

    private boolean placeVehicleOnTargetLane(
            Vehicle v,
            Intersection ixtn,
            Road outRoad,
            Lane targetLane,
            Map<String, Double> lastPlacedPosition) {
        double outBuffer = IntersectionGeometry.computeStopLineBuffer(ixtn, outRoad);
        double lastPos = lastPlacedPosition.getOrDefault(targetLane.getId(), -1.0);
        double effectivePosition = (lastPos >= outBuffer) ? lastPos + MIN_ENTRY_GAP : outBuffer;

        // If target lane is too congested (no room even 3 gaps beyond entry), skip
        if (effectivePosition > outBuffer + 3 * MIN_ENTRY_GAP) {
            return false;
        }

        // Check space on target lane at the effective entry point
        Vehicle firstOnTarget = targetLane.findLeaderAt(effectivePosition - 1.0);
        if (firstOnTarget != null
                && firstOnTarget.getPosition() < effectivePosition + MIN_ENTRY_GAP) {
            return false; // no space
        }

        v.updatePhysics(effectivePosition, v.getSpeed(), v.getAcceleration());
        v.setLane(targetLane); // transfer = special case, not a lane change
        v.completeLaneChange();
        targetLane.addVehicle(v);
        lastPlacedPosition.put(targetLane.getId(), effectivePosition);

        log.debug(
                "Vehicle {} transferred through intersection {} from {} to {} at pos {}",
                v.getId(),
                ixtn.getId(),
                v.getLane() != null ? v.getLane().getId() : "?",
                targetLane.getId(),
                effectivePosition);
        return true;
    }

    private String pickOutboundRoad(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        List<String> candidates = new ArrayList<>();
        for (String outId : ixtn.getOutboundRoadIds()) {
            if (isValidOutboundCandidate(outId, inboundRoadId, network)) {
                candidates.add(outId);
            }
        }
        if (candidates.isEmpty() && !ixtn.getOutboundRoadIds().isEmpty()) {
            // Allow U-turn as fallback
            return ixtn.getOutboundRoadIds()
                    .get(ThreadLocalRandom.current().nextInt(ixtn.getOutboundRoadIds().size()));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private boolean isValidOutboundCandidate(
            String outId, String inboundRoadId, RoadNetwork network) {
        return !outId.equals(IntersectionGeometry.reverseRoadId(inboundRoadId))
                && network.getRoads().get(outId) != null;
    }

    private Lane pickTargetLane(Road outRoad, Road inboundRoad) {
        List<Lane> active = outRoad.getLanes().stream().filter(Lane::isActive).toList();
        if (active.isEmpty()) {
            return null;
        }

        // Merge scenario: inbound has fewer lanes than outbound -> target rightmost lane (index 0)
        if (inboundRoad != null && inboundRoad.getLanes().size() < outRoad.getLanes().size()) {
            // Pick rightmost active lane (lowest index)
            return active.stream()
                    .min(Comparator.comparingInt(lane -> outRoad.getLanes().indexOf(lane)))
                    .orElse(active.get(0));
        }

        // Default: random active lane
        return active.get(ThreadLocalRandom.current().nextInt(active.size()));
    }

    private Lane pickTargetLane(Road road) {
        return pickTargetLane(road, null);
    }

    /** Delegates to {@link RoundaboutManager#countRoundaboutOccupancy}. */
    int countRoundaboutOccupancy(Intersection ixtn, RoadNetwork network) {
        return roundaboutManager.countRoundaboutOccupancy(ixtn, network);
    }
}
