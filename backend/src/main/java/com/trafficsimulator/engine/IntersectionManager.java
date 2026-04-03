package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Comparator;

@Component
@Slf4j
public class IntersectionManager implements IIntersectionManager {

    private static final double STOP_LINE_BUFFER_DEFAULT = 2.0;  // metres before road end (fallback)
    private static final double MIN_ENTRY_GAP = 7.0;     // vehicle length + s0
    private static final int DEADLOCK_THRESHOLD_TICKS = 200;  // 200 ticks = 10s at 20Hz
    private static final double ROUNDABOUT_YIELD_ZONE = 15.0; // metres — check circulating vehicles within this distance
    private static final double ROUNDABOUT_GATING_RATIO = 0.8; // 80% capacity triggers entry gating

    private final Map<String, IntersectionState> intersectionStates = new HashMap<>();

    private static class IntersectionState {
        long lastTransferTick = 0;
        int waitingVehicleCount = 0;
    }

    /**
     * Builds a map of laneId -> stopLinePosition for all lanes that should have
     * a red-light or box-blocking stop. Called each tick before physics.
     */
    @Override
    public Map<String, Double> computeStopLines(RoadNetwork network) {
        Map<String, Double> stopLines = new HashMap<>();
        for (Intersection ixtn : network.getIntersections().values()) {
            for (String inboundRoadId : ixtn.getInboundRoadIds()) {
                Road inboundRoad = network.getRoads().get(inboundRoadId);
                if (inboundRoad == null) continue;

                boolean canEnter = canEnterIntersection(ixtn, inboundRoadId, network);

                if (!canEnter) {
                    double buffer = computeStopLineBuffer(ixtn, inboundRoad);
                    double stopPos = Math.max(0, inboundRoad.getLength() - buffer);
                    for (Lane lane : inboundRoad.getLanes()) {
                        stopLines.put(lane.getId(), stopPos);
                    }
                }
            }
        }
        return stopLines;
    }

    /**
     * Computes stop line buffer in domain units based on the intersection's pixel size
     * and the road's pixel-to-domain ratio. This ensures vehicles stop at the visual
     * edge of the intersection box, not inside it.
     */
    private double computeStopLineBuffer(Intersection ixtn, Road road) {
        if (ixtn.getIntersectionSize() <= 0) return STOP_LINE_BUFFER_DEFAULT;
        double halfSizePx = ixtn.getIntersectionSize() / 2.0;
        double pixelLength = Math.sqrt(
            Math.pow(road.getEndX() - road.getStartX(), 2) +
            Math.pow(road.getEndY() - road.getStartY(), 2));
        if (pixelLength < 1) return STOP_LINE_BUFFER_DEFAULT;
        // Convert pixel half-size to domain units using the road's scale
        return halfSizePx * (road.getLength() / pixelLength) + 1.0; // +1m margin
    }

    /**
     * Checks if a vehicle on the given inbound road can enter the intersection.
     * Considers: traffic light state, right-of-way priority, roundabout yield, and box-blocking.
     */
    private boolean canEnterIntersection(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        if (ixtn.getType() == IntersectionType.SIGNAL && ixtn.getTrafficLight() != null) {
            if (!ixtn.getTrafficLight().isGreen(inboundRoadId)) return false;
        }

        if (ixtn.getType() == IntersectionType.PRIORITY || ixtn.getType() == IntersectionType.NONE) {
            if (hasVehicleFromRight(ixtn, inboundRoadId, network)) return false;
        }

        if (ixtn.getType() == IntersectionType.ROUNDABOUT) {
            if (isRoundaboutEntryBlocked(ixtn, inboundRoadId, network)) return false;
        }

        return checkBoxBlocking(ixtn, inboundRoadId, network);
    }

    /**
     * Box-blocking check: returns true if at least one outbound road has space at clip edge entry.
     */
    private boolean checkBoxBlocking(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        for (String outRoadId : ixtn.getOutboundRoadIds()) {
            if (outRoadId.equals(reverseRoadId(inboundRoadId))) continue; // skip U-turn
            Road outRoad = network.getRoads().get(outRoadId);
            if (outRoad == null) continue;
            double outBuffer = computeStopLineBuffer(ixtn, outRoad);
            for (Lane outLane : outRoad.getLanes()) {
                if (!outLane.isActive()) continue;
                Vehicle first = outLane.findLeaderAt(outBuffer - 1.0);
                if (first == null || first.getPosition() >= outBuffer + MIN_ENTRY_GAP) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Roundabout entry check. Returns true if entry should be BLOCKED.
     * Approach roads (from outside) yield to ring roads (circulating traffic).
     * Ring roads never yield — they have priority.
     */
    private boolean isRoundaboutEntryBlocked(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        Road myRoad = network.getRoads().get(inboundRoadId);
        if (myRoad == null) return false;

        boolean isRingRoad = network.getIntersections().containsKey(myRoad.getFromNodeId());
        if (isRingRoad) return false;

        if (hasCirculatingRingTraffic(ixtn, inboundRoadId, network)) return true;

        return isRoundaboutCapacityExceeded(ixtn, network);
    }

    private boolean hasCirculatingRingTraffic(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        for (String otherInboundId : ixtn.getInboundRoadIds()) {
            if (otherInboundId.equals(inboundRoadId)) continue;
            Road otherRoad = network.getRoads().get(otherInboundId);
            if (otherRoad == null) continue;

            boolean otherIsRing = network.getIntersections().containsKey(otherRoad.getFromNodeId());
            if (!otherIsRing) continue;

            if (hasVehiclesNearEnd(otherRoad, ROUNDABOUT_YIELD_ZONE)) return true;
        }
        return false;
    }

    private boolean hasVehiclesNearEnd(Road road, double zone) {
        double threshold = road.getLength() - zone;
        for (Lane lane : road.getLanes()) {
            for (Vehicle v : lane.getVehiclesView()) {
                if (v.getPosition() >= threshold) return true;
            }
        }
        return false;
    }

    private boolean isRoundaboutCapacityExceeded(Intersection ixtn, RoadNetwork network) {
        int capacity = ixtn.getRoundaboutCapacity();
        if (capacity <= 0) return false;
        int occupancy = countRoundaboutOccupancy(ixtn, network);
        return occupancy >= (int)(capacity * ROUNDABOUT_GATING_RATIO);
    }

    /**
     * Counts vehicles on ring roads connected to this intersection (circulating traffic).
     */
    int countRoundaboutOccupancy(Intersection ixtn, RoadNetwork network) {
        int count = 0;
        // Count vehicles on all ring inbound roads
        for (String inRoadId : ixtn.getInboundRoadIds()) {
            Road inRoad = network.getRoads().get(inRoadId);
            if (inRoad == null) continue;
            boolean isRing = network.getIntersections().containsKey(inRoad.getFromNodeId());
            if (!isRing) continue;
            for (Lane lane : inRoad.getLanes()) {
                count += lane.getVehicleCount();
            }
        }
        // Count vehicles on all ring outbound roads
        for (String outRoadId : ixtn.getOutboundRoadIds()) {
            Road outRoad = network.getRoads().get(outRoadId);
            if (outRoad == null) continue;
            boolean isRing = network.getIntersections().containsKey(outRoad.getToNodeId());
            if (!isRing) continue;
            for (Lane lane : outRoad.getLanes()) {
                count += lane.getVehicleCount();
            }
        }
        return count;
    }

    /**
     * Returns true if any vehicle is approaching the intersection from the right
     * of the given inbound road. Uses geometric angle calculation.
     */
    private boolean hasVehicleFromRight(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        Road myRoad = network.getRoads().get(inboundRoadId);
        if (myRoad == null) return false;

        double myAngle = Math.atan2(myRoad.getEndY() - myRoad.getStartY(),
                                     myRoad.getEndX() - myRoad.getStartX());

        for (String otherRoadId : ixtn.getInboundRoadIds()) {
            if (otherRoadId.equals(inboundRoadId)) continue;
            Road otherRoad = network.getRoads().get(otherRoadId);
            if (otherRoad == null) continue;

            double threshold = otherRoad.getLength() - STOP_LINE_BUFFER_DEFAULT - 10.0;
            if (!hasWaitingVehicleOnRoad(otherRoad, threshold)) continue;

            double otherAngle = Math.atan2(otherRoad.getEndY() - otherRoad.getStartY(),
                                            otherRoad.getEndX() - otherRoad.getStartX());
            if (isApproachFromRight(myAngle, otherAngle)) return true;
        }
        return false;
    }

    /**
     * Returns true if any vehicle on the given road is at or past the threshold position.
     */
    private boolean hasWaitingVehicleOnRoad(Road road, double threshold) {
        for (Lane lane : road.getLanes()) {
            for (Vehicle v : lane.getVehiclesView()) {
                if (v.getPosition() >= threshold) return true;
            }
        }
        return false;
    }

    /**
     * Returns true if otherAngle is approximately 90 degrees clockwise from myAngle
     * (i.e., the other road approaches from the right).
     */
    private boolean isApproachFromRight(double myAngle, double otherAngle) {
        double diff = normalizeAngle(otherAngle - myAngle);
        // diff around -PI/2 means from the right
        return diff > -Math.PI * 0.75 && diff < -Math.PI * 0.25;
    }

    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle <= -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * Transfers vehicles that have reached the end of inbound roads through intersections
     * to outbound roads. Called each tick after physics, before despawn.
     */
    @Override
    public void processTransfers(RoadNetwork network, long currentTick) {
        for (Intersection ixtn : network.getIntersections().values()) {
            Map<String, Double> lastPlaced = new HashMap<>();

            int waitingCount = countWaitingVehicles(ixtn, network);
            boolean transferredAny = transferFromInboundRoads(ixtn, network, lastPlaced);

            IntersectionState state = intersectionStates.computeIfAbsent(
                ixtn.getId(), k -> new IntersectionState());
            state.waitingVehicleCount = waitingCount;
            if (transferredAny) {
                state.lastTransferTick = currentTick;
            }
        }

        checkDeadlocks(network, currentTick);
    }

    /**
     * Counts vehicles waiting at stop lines across all inbound roads of the intersection.
     */
    private int countWaitingVehicles(Intersection ixtn, RoadNetwork network) {
        int waitingCount = 0;
        for (String inboundRoadId : ixtn.getInboundRoadIds()) {
            Road inboundRoad = network.getRoads().get(inboundRoadId);
            if (inboundRoad == null) continue;
            double buffer = computeStopLineBuffer(ixtn, inboundRoad);
            double waitThreshold = inboundRoad.getLength() - buffer - 5.0;
            for (Lane lane : inboundRoad.getLanes()) {
                for (Vehicle v : lane.getVehiclesView()) {
                    if (v.getPosition() >= waitThreshold && v.getSpeed() < 0.5) {
                        waitingCount++;
                    }
                }
            }
        }
        return waitingCount;
    }

    /**
     * Iterates inbound roads, checks canEnterIntersection, and transfers vehicles per lane.
     * Returns true if any transfer occurred.
     */
    private boolean transferFromInboundRoads(Intersection ixtn, RoadNetwork network,
                                              Map<String, Double> lastPlaced) {
        boolean transferredAny = false;
        for (String inboundRoadId : ixtn.getInboundRoadIds()) {
            Road inboundRoad = network.getRoads().get(inboundRoadId);
            if (inboundRoad == null) continue;
            if (!canEnterIntersection(ixtn, inboundRoadId, network)) continue;
            for (Lane inLane : inboundRoad.getLanes()) {
                if (transferVehiclesFromLane(ixtn, inboundRoadId, inLane, network, lastPlaced)) {
                    transferredAny = true;
                }
            }
        }
        return transferredAny;
    }

    /**
     * Detects deadlocked intersections (2+ approaches blocked with no transfer for
     * DEADLOCK_THRESHOLD_TICKS) and force-advances the longest-waiting vehicle.
     */
    private void checkDeadlocks(RoadNetwork network, long currentTick) {
        for (Intersection ixtn : network.getIntersections().values()) {
            IntersectionState state = intersectionStates.computeIfAbsent(
                ixtn.getId(), k -> new IntersectionState());

            if (state.waitingVehicleCount < 2) continue;
            long ticksSinceTransfer = currentTick - state.lastTransferTick;
            if (ticksSinceTransfer < DEADLOCK_THRESHOLD_TICKS) continue;

            resolveDeadlock(ixtn, network, state, currentTick, ticksSinceTransfer);
        }
    }

    /**
     * Resolves a detected deadlock by force-advancing the longest-waiting vehicle.
     */
    private void resolveDeadlock(Intersection ixtn, RoadNetwork network,
                                  IntersectionState state, long currentTick, long ticksSinceTransfer) {
        Vehicle victim = findLongestWaitingVehicle(ixtn, network);
        if (victim == null) return;

        forceTransferVehicle(victim, ixtn, network);
        state.lastTransferTick = currentTick;

        log.warn("Deadlock resolved at intersection {} by force-advancing vehicle {} (waited {} ticks)",
            ixtn.getId(), victim.getId(), ticksSinceTransfer);
    }

    private Vehicle findLongestWaitingVehicle(Intersection ixtn, RoadNetwork network) {
        Vehicle oldest = null;
        for (String inRoadId : ixtn.getInboundRoadIds()) {
            Vehicle candidate = findOldestStoppedVehicleOnRoad(inRoadId, ixtn, network);
            if (candidate != null && (oldest == null || candidate.getSpawnedAt() < oldest.getSpawnedAt())) {
                oldest = candidate;
            }
        }
        return oldest;
    }

    private Vehicle findOldestStoppedVehicleOnRoad(String inRoadId, Intersection ixtn, RoadNetwork network) {
        Road inRoad = network.getRoads().get(inRoadId);
        if (inRoad == null) return null;
        double buffer = computeStopLineBuffer(ixtn, inRoad);
        double threshold = inRoad.getLength() - buffer - 5.0;
        Vehicle oldest = null;
        for (Lane lane : inRoad.getLanes()) {
            for (Vehicle v : lane.getVehiclesView()) {
                if (v.getPosition() < threshold || v.getSpeed() >= 0.5) continue;
                if (oldest == null || v.getSpawnedAt() < oldest.getSpawnedAt()) {
                    oldest = v;
                }
            }
        }
        return oldest;
    }

    private void forceTransferVehicle(Vehicle victim, Intersection ixtn, RoadNetwork network) {
        // Find victim's current lane and remove
        Lane victimLane = victim.getLane();
        if (victimLane == null) return;
        victimLane.removeVehicle(victim);

        // Pick any outbound road
        if (ixtn.getOutboundRoadIds().isEmpty()) return;
        String outRoadId = ixtn.getOutboundRoadIds().get(
            ThreadLocalRandom.current().nextInt(ixtn.getOutboundRoadIds().size()));
        Road outRoad = network.getRoads().get(outRoadId);
        if (outRoad == null) return;

        Lane targetLane = pickTargetLane(outRoad);
        if (targetLane == null) return;

        // Force transfer -- ignores space check; start at clip edge
        double outBuffer = computeStopLineBuffer(
            ixtn, outRoad);
        victim.updatePhysics(outBuffer, victim.getSpeed(), victim.getAcceleration());
        victim.setLane(targetLane);  // transfer = special case, not a lane change
        victim.completeLaneChange();
        targetLane.addVehicle(victim);
    }

    /**
     * Transfers vehicles from a lane through an intersection.
     * Returns true if at least one vehicle was transferred.
     *
     * @param lastPlacedPosition shared map (laneId -> last placed position) across all
     *                           transferVehiclesFromLane calls within one processTransfers tick.
     *                           Prevents vehicles from different inbound roads from being placed
     *                           at the same position on the same target lane.
     */
    private boolean transferVehiclesFromLane(Intersection ixtn, String inboundRoadId,
                                           Lane inLane, RoadNetwork network,
                                           Map<String, Double> lastPlacedPosition) {
        Road inboundRoad = network.getRoads().get(inboundRoadId);
        double buffer = inboundRoad != null ? computeStopLineBuffer(ixtn, inboundRoad) : STOP_LINE_BUFFER_DEFAULT;
        double threshold = inLane.getLength() - buffer;
        List<Vehicle> toTransfer = new ArrayList<>();
        boolean transferred = false;

        for (Vehicle v : inLane.getVehiclesView()) {
            if (v.getPosition() < threshold) continue;
            if (tryTransferVehicle(v, ixtn, inboundRoadId, inboundRoad, network, lastPlacedPosition)) {
                toTransfer.add(v);
                transferred = true;
            }
        }

        for (Vehicle v : toTransfer) {
            inLane.removeVehicle(v);
        }
        return transferred;
    }

    /**
     * Attempts to transfer a single vehicle through the intersection to an outbound road.
     * Returns true if the transfer succeeded.
     */
    private boolean tryTransferVehicle(Vehicle v, Intersection ixtn, String inboundRoadId,
                                        Road inboundRoad, RoadNetwork network,
                                        Map<String, Double> lastPlacedPosition) {
        String outRoadId = pickOutboundRoad(ixtn, inboundRoadId, network);
        if (outRoadId == null) return false;

        Road outRoad = network.getRoads().get(outRoadId);
        if (outRoad == null) return false;

        Lane targetLane = pickTargetLane(outRoad, inboundRoad);
        if (targetLane == null) return false;

        double outBuffer = computeStopLineBuffer(ixtn, outRoad);
        double lastPos = lastPlacedPosition.getOrDefault(targetLane.getId(), -1.0);
        double effectivePosition = (lastPos >= outBuffer) ? lastPos + MIN_ENTRY_GAP : outBuffer;

        // If target lane is too congested (no room even 3 gaps beyond entry), skip
        if (effectivePosition > outBuffer + 3 * MIN_ENTRY_GAP) return false;

        // Check space on target lane at the effective entry point
        Vehicle firstOnTarget = targetLane.findLeaderAt(effectivePosition - 1.0);
        if (firstOnTarget != null && firstOnTarget.getPosition() < effectivePosition + MIN_ENTRY_GAP) {
            return false; // no space
        }

        v.updatePhysics(effectivePosition, v.getSpeed(), v.getAcceleration());
        v.setLane(targetLane);  // transfer = special case, not a lane change
        v.completeLaneChange();
        targetLane.addVehicle(v);
        lastPlacedPosition.put(targetLane.getId(), effectivePosition);

        log.debug("Vehicle {} transferred through intersection {} from {} to {} at pos {}",
            v.getId(), ixtn.getId(), v.getLane() != null ? v.getLane().getId() : "?",
            targetLane.getId(), effectivePosition);
        return true;
    }

    private String pickOutboundRoad(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        List<String> candidates = new ArrayList<>();
        for (String outId : ixtn.getOutboundRoadIds()) {
            // Exclude U-turn (same direction back)
            if (outId.equals(reverseRoadId(inboundRoadId))) continue;
            Road outRoad = network.getRoads().get(outId);
            if (outRoad == null) continue;
            candidates.add(outId);
        }
        if (candidates.isEmpty()) {
            // Allow U-turn as fallback
            if (!ixtn.getOutboundRoadIds().isEmpty()) {
                return ixtn.getOutboundRoadIds().get(
                    ThreadLocalRandom.current().nextInt(ixtn.getOutboundRoadIds().size()));
            }
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private Lane pickTargetLane(Road outRoad, Road inboundRoad) {
        List<Lane> active = outRoad.getLanes().stream()
            .filter(Lane::isActive)
            .toList();
        if (active.isEmpty()) return null;

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

    /**
     * Simple heuristic: "r_north_in" -> "r_north_out" is a U-turn.
     * Replace "_in" with "_out" to guess the reverse road ID.
     * Not all maps follow this convention, so this is best-effort.
     */
    private String reverseRoadId(String inboundRoadId) {
        return inboundRoadId.replace("_in", "_out");
    }
}
