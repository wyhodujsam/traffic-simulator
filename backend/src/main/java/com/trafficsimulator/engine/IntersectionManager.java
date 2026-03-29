package com.trafficsimulator.engine;

import com.trafficsimulator.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
public class IntersectionManager {

    private static final double STOP_LINE_BUFFER = 2.0;  // metres before road end
    private static final double MIN_ENTRY_GAP = 7.0;     // vehicle length + s0

    /**
     * Builds a map of laneId -> stopLinePosition for all lanes that should have
     * a red-light or box-blocking stop. Called each tick before physics.
     */
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
     * Considers: traffic light state + box-blocking (outbound road capacity).
     */
    private boolean canEnterIntersection(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        // 1. Traffic light check for SIGNAL intersections
        if (ixtn.getType() == IntersectionType.SIGNAL && ixtn.getTrafficLight() != null) {
            if (!ixtn.getTrafficLight().isGreen(inboundRoadId)) {
                return false;
            }
        }

        // 2. Box-blocking check: at least one outbound road must have space at entry
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
     * Transfers vehicles that have reached the end of inbound roads through intersections
     * to outbound roads. Called each tick after physics, before despawn.
     */
    public void processTransfers(RoadNetwork network, long currentTick) {
        for (Intersection ixtn : network.getIntersections().values()) {
            for (String inboundRoadId : ixtn.getInboundRoadIds()) {
                Road inboundRoad = network.getRoads().get(inboundRoadId);
                if (inboundRoad == null) continue;

                if (!canEnterIntersection(ixtn, inboundRoadId, network)) continue;

                for (Lane inLane : inboundRoad.getLanes()) {
                    transferVehiclesFromLane(ixtn, inboundRoadId, inLane, network);
                }
            }
        }
    }

    private void transferVehiclesFromLane(Intersection ixtn, String inboundRoadId,
                                           Lane inLane, RoadNetwork network) {
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

            log.debug("Vehicle {} transferred through intersection {} from {} to {}",
                v.getId(), ixtn.getId(), inLane.getId(), targetLane.getId());
        }
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
