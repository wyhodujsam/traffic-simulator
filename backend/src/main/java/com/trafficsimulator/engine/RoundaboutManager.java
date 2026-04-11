package com.trafficsimulator.engine;

import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;

/** Encapsulates roundabout-specific yield, capacity, and entry-blocking logic. */
class RoundaboutManager {

    static final double ROUNDABOUT_YIELD_ZONE =
            15.0; // metres — check circulating vehicles within this distance
    static final double ROUNDABOUT_GATING_RATIO = 0.8; // 80% capacity triggers entry gating

    /**
     * Roundabout entry check. Returns true if entry should be BLOCKED. Approach roads (from
     * outside) yield to ring roads (circulating traffic). Ring roads never yield — they have
     * priority.
     */
    boolean isRoundaboutEntryBlocked(Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        Road myRoad = network.getRoads().get(inboundRoadId);
        if (myRoad == null) {
            return false;
        }

        boolean isRingRoad = network.getIntersections().containsKey(myRoad.getFromNodeId());
        if (isRingRoad) {
            return false;
        }

        return hasCirculatingRingTraffic(ixtn, inboundRoadId, network)
                || isRoundaboutCapacityExceeded(ixtn, network);
    }

    boolean hasCirculatingRingTraffic(
            Intersection ixtn, String inboundRoadId, RoadNetwork network) {
        return ixtn.getInboundRoadIds().stream()
                .anyMatch(
                        otherInboundId ->
                                isCirculatingRingRoad(otherInboundId, inboundRoadId, network));
    }

    boolean isCirculatingRingRoad(
            String otherInboundId, String inboundRoadId, RoadNetwork network) {
        if (otherInboundId.equals(inboundRoadId)) {
            return false;
        }
        Road otherRoad = network.getRoads().get(otherInboundId);
        if (otherRoad == null) {
            return false;
        }
        boolean otherIsRing = network.getIntersections().containsKey(otherRoad.getFromNodeId());
        return otherIsRing && hasVehiclesNearEnd(otherRoad, ROUNDABOUT_YIELD_ZONE);
    }

    boolean hasVehiclesNearEnd(Road road, double zone) {
        double threshold = road.getLength() - zone;
        return road.getLanes().stream()
                .flatMap(lane -> lane.getVehiclesView().stream())
                .anyMatch(v -> v.getPosition() >= threshold);
    }

    boolean isRoundaboutCapacityExceeded(Intersection ixtn, RoadNetwork network) {
        int capacity = ixtn.getRoundaboutCapacity();
        if (capacity <= 0) {
            return false;
        }
        int occupancy = countRoundaboutOccupancy(ixtn, network);
        return occupancy >= (int) (capacity * ROUNDABOUT_GATING_RATIO);
    }

    /** Counts vehicles on ring roads connected to this intersection (circulating traffic). */
    int countRoundaboutOccupancy(Intersection ixtn, RoadNetwork network) {
        int count = 0;
        for (String inRoadId : ixtn.getInboundRoadIds()) {
            count += countRingRoadVehicles(inRoadId, network, true);
        }
        for (String outRoadId : ixtn.getOutboundRoadIds()) {
            count += countRingRoadVehicles(outRoadId, network, false);
        }
        return count;
    }

    int countRingRoadVehicles(String roadId, RoadNetwork network, boolean inbound) {
        Road road = network.getRoads().get(roadId);
        if (road == null) {
            return 0;
        }
        String nodeId = inbound ? road.getFromNodeId() : road.getToNodeId();
        if (!network.getIntersections().containsKey(nodeId)) {
            return 0;
        }
        return road.getLanes().stream().mapToInt(Lane::getVehicleCount).sum();
    }
}
