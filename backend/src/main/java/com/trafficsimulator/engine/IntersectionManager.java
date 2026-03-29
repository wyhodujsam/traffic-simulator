package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
public class IntersectionManager implements IIntersectionManager {

    private static final double STOP_LINE_BUFFER = 2.0;  // metres before road end
    private static final double MIN_ENTRY_GAP = 7.0;     // vehicle length + s0
    private static final int DEADLOCK_THRESHOLD_TICKS = 200;  // 200 ticks = 10s at 20Hz

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
                    double stopPos = inboundRoad.getLength() - STOP_LINE_BUFFER;
                    for (Lane lane : inboundRoad.getLanes()) {
                        stopLines.put(lane.getId(), stopPos);
                    }
                }
            }
        }
        return stopLines;
    }

    /**
     * Checks if a vehicle on the given inbound road can enter the intersection.
     * Considers: traffic light state, right-of-way priority, and box-blocking (outbound road capacity).
     */
    private boolean canEnterIntersection(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        // 1. Traffic light check for SIGNAL intersections
        if (ixtn.getType() == IntersectionType.SIGNAL && ixtn.getTrafficLight() != null) {
            if (!ixtn.getTrafficLight().isGreen(inboundRoadId)) {
                return false;
            }
        }

        // 2. Right-of-way check for PRIORITY/NONE intersections
        if (ixtn.getType() == IntersectionType.PRIORITY || ixtn.getType() == IntersectionType.NONE) {
            if (hasVehicleFromRight(ixtn, inboundRoadId, network)) {
                return false;
            }
        }

        // 3. Box-blocking check: at least one outbound road must have space at entry
        boolean anyOutboundHasSpace = false;
        for (String outRoadId : ixtn.getOutboundRoadIds()) {
            if (outRoadId.equals(reverseRoadId(inboundRoadId))) continue; // skip U-turn
            Road outRoad = network.getRoads().get(outRoadId);
            if (outRoad == null) continue;
            for (Lane outLane : outRoad.getLanes()) {
                if (!outLane.isActive()) continue;
                Vehicle first = outLane.findLeaderAt(-1.0);
                if (first == null || first.getPosition() >= MIN_ENTRY_GAP) {
                    anyOutboundHasSpace = true;
                    break;
                }
            }
            if (anyOutboundHasSpace) break;
        }

        return anyOutboundHasSpace;
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

            // Check if other road has a vehicle near the intersection
            boolean hasWaitingVehicle = false;
            double threshold = otherRoad.getLength() - STOP_LINE_BUFFER - 10.0;
            for (Lane lane : otherRoad.getLanes()) {
                for (Vehicle v : lane.getVehicles()) {
                    if (v.getPosition() >= threshold) {
                        hasWaitingVehicle = true;
                        break;
                    }
                }
                if (hasWaitingVehicle) break;
            }
            if (!hasWaitingVehicle) continue;

            double otherAngle = Math.atan2(otherRoad.getEndY() - otherRoad.getStartY(),
                                            otherRoad.getEndX() - otherRoad.getStartX());

            // "Right" = 90 degrees clockwise from my approach direction
            // If otherAngle is approximately myAngle - PI/2 (within 45 degrees), it's from my right
            double diff = normalizeAngle(otherAngle - myAngle);
            // diff around -PI/2 means from the right
            if (diff > -Math.PI * 0.75 && diff < -Math.PI * 0.25) {
                return true;  // vehicle from the right has priority
            }
        }
        return false;
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
            boolean transferredAny = false;
            int waitingCount = 0;

            for (String inboundRoadId : ixtn.getInboundRoadIds()) {
                Road inboundRoad = network.getRoads().get(inboundRoadId);
                if (inboundRoad == null) continue;

                // Count vehicles waiting at stop lines across all inbound roads
                double waitThreshold = inboundRoad.getLength() - STOP_LINE_BUFFER - 5.0;
                for (Lane lane : inboundRoad.getLanes()) {
                    for (Vehicle v : lane.getVehicles()) {
                        if (v.getPosition() >= waitThreshold && v.getSpeed() < 0.5) {
                            waitingCount++;
                        }
                    }
                }

                if (!canEnterIntersection(ixtn, inboundRoadId, network)) continue;

                for (Lane inLane : inboundRoad.getLanes()) {
                    if (transferVehiclesFromLane(ixtn, inboundRoadId, inLane, network)) {
                        transferredAny = true;
                    }
                }
            }

            // Update intersection state
            IntersectionState state = intersectionStates.computeIfAbsent(
                ixtn.getId(), k -> new IntersectionState());
            state.waitingVehicleCount = waitingCount;
            if (transferredAny) {
                state.lastTransferTick = currentTick;
            }
        }

        // Check for deadlocks after processing all intersections
        checkDeadlocks(network, currentTick);
    }

    /**
     * Detects deadlocked intersections (2+ approaches blocked with no transfer for
     * DEADLOCK_THRESHOLD_TICKS) and force-advances the longest-waiting vehicle.
     */
    private void checkDeadlocks(RoadNetwork network, long currentTick) {
        for (Intersection ixtn : network.getIntersections().values()) {
            IntersectionState state = intersectionStates.computeIfAbsent(
                ixtn.getId(), k -> new IntersectionState());

            if (state.waitingVehicleCount < 2) continue;  // need at least 2 blocked approaches

            long ticksSinceTransfer = currentTick - state.lastTransferTick;
            if (ticksSinceTransfer < DEADLOCK_THRESHOLD_TICKS) continue;

            // DEADLOCK DETECTED -- force-advance the longest-waiting vehicle
            Vehicle victim = findLongestWaitingVehicle(ixtn, network);
            if (victim == null) continue;

            forceTransferVehicle(victim, ixtn, network);
            state.lastTransferTick = currentTick;

            log.warn("Deadlock resolved at intersection {} by force-advancing vehicle {} (waited {} ticks)",
                ixtn.getId(), victim.getId(), ticksSinceTransfer);
        }
    }

    private Vehicle findLongestWaitingVehicle(Intersection ixtn, RoadNetwork network) {
        Vehicle oldest = null;
        for (String inRoadId : ixtn.getInboundRoadIds()) {
            Road inRoad = network.getRoads().get(inRoadId);
            if (inRoad == null) continue;
            double threshold = inRoad.getLength() - STOP_LINE_BUFFER - 5.0;
            for (Lane lane : inRoad.getLanes()) {
                for (Vehicle v : lane.getVehicles()) {
                    if (v.getPosition() >= threshold && v.getSpeed() < 0.5) {
                        if (oldest == null || v.getSpawnedAt() < oldest.getSpawnedAt()) {
                            oldest = v;
                        }
                    }
                }
            }
        }
        return oldest;
    }

    private void forceTransferVehicle(Vehicle victim, Intersection ixtn, RoadNetwork network) {
        // Find victim's current lane and remove
        Lane victimLane = victim.getLane();
        if (victimLane == null) return;
        victimLane.getVehicles().remove(victim);

        // Pick any outbound road
        if (ixtn.getOutboundRoadIds().isEmpty()) return;
        String outRoadId = ixtn.getOutboundRoadIds().get(
            ThreadLocalRandom.current().nextInt(ixtn.getOutboundRoadIds().size()));
        Road outRoad = network.getRoads().get(outRoadId);
        if (outRoad == null) return;

        Lane targetLane = pickTargetLane(outRoad);
        if (targetLane == null) return;

        // Force transfer -- ignores space check
        victim.setPosition(0.0);
        victim.setLane(targetLane);
        victim.setLaneChangeSourceIndex(-1);
        victim.setLaneChangeProgress(1.0);
        targetLane.getVehicles().add(victim);
    }

    /**
     * Transfers vehicles from a lane through an intersection.
     * Returns true if at least one vehicle was transferred.
     */
    private boolean transferVehiclesFromLane(Intersection ixtn, String inboundRoadId,
                                           Lane inLane, RoadNetwork network) {
        boolean transferred = false;
        double threshold = inLane.getLength() - STOP_LINE_BUFFER;
        Iterator<Vehicle> iter = inLane.getVehicles().iterator();
        while (iter.hasNext()) {
            Vehicle v = iter.next();
            if (v.getPosition() < threshold) continue;

            // Pick a random outbound road (excluding U-turn)
            String outRoadId = pickOutboundRoad(ixtn, inboundRoadId, network);
            if (outRoadId == null) continue;

            Road outRoad = network.getRoads().get(outRoadId);
            if (outRoad == null) continue;

            // Pick a lane on the outbound road that has space
            Lane targetLane = pickTargetLane(outRoad);
            if (targetLane == null) continue;

            // Check space on target lane
            Vehicle firstOnTarget = targetLane.findLeaderAt(-1.0);
            if (firstOnTarget != null && firstOnTarget.getPosition() < MIN_ENTRY_GAP) {
                continue; // no space
            }

            // Transfer: remove from inbound, add to outbound at position 0
            iter.remove();
            v.setPosition(0.0);
            v.setLane(targetLane);
            v.setLaneChangeSourceIndex(-1);
            v.setLaneChangeProgress(1.0);
            targetLane.getVehicles().add(v);
            transferred = true;

            log.debug("Vehicle {} transferred through intersection {} from {} to {}",
                v.getId(), ixtn.getId(), inLane.getId(), targetLane.getId());
        }
        return transferred;
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

    private Lane pickTargetLane(Road road) {
        List<Lane> active = road.getLanes().stream()
            .filter(Lane::isActive)
            .toList();
        if (active.isEmpty()) return null;
        return active.get(ThreadLocalRandom.current().nextInt(active.size()));
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
