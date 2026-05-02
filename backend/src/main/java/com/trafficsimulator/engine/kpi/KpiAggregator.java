package com.trafficsimulator.engine.kpi;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trafficsimulator.dto.IntersectionKpiDto;
import com.trafficsimulator.dto.KpiDto;
import com.trafficsimulator.dto.SegmentKpiDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

import lombok.RequiredArgsConstructor;

/**
 * Composes the Phase 25 KPI suite (D-05/06/07/08): throughput (delegates to {@link
 * IVehicleSpawner#getThroughput(long)}), mean delay (reads {@link DelayWindow}), per-segment +
 * per-intersection density / flow / queue / LOS via {@link LosClassifier} and {@link
 * QueueAnalyzer}.
 *
 * <p>Pure compute — no caching here. Sub-sampling (every 5 ticks) is the responsibility of {@link
 * com.trafficsimulator.scheduler.SnapshotBuilder} per D-08.
 */
@Component
@RequiredArgsConstructor
public class KpiAggregator implements IKpiAggregator {

    private final DelayWindow delayWindow;

    @Override
    public KpiDto computeNetworkKpi(
            RoadNetwork network, long currentTick, IVehicleSpawner spawner) {
        // Walk segments inline (NOT via computeSegmentKpis) so spies can count list-recomputes
        // separately from the per-tick network roll-up. KPI-05 sub-sampling is enforced upstream
        // in SnapshotBuilder by gating the public list calls.
        String worstLos = "A";
        double maxQueue = 0.0;
        if (network != null && network.getRoads() != null) {
            for (Road road : network.getRoads().values()) {
                SegmentKpiDto s = computeOneSegmentKpi(road);
                worstLos = LosClassifier.worse(worstLos, s.getLos());
                if (s.getP95QueueLengthMeters() > maxQueue) {
                    maxQueue = s.getP95QueueLengthMeters();
                }
            }
        }
        double throughput = spawner == null ? 0.0 : spawner.getThroughput(currentTick);
        double meanDelay = delayWindow == null ? 0.0 : delayWindow.meanDelay(currentTick);
        return KpiDto.builder()
                .throughputVehiclesPerMin(throughput)
                .meanDelaySeconds(meanDelay)
                .p95QueueLengthMeters(maxQueue)
                .worstLos(worstLos)
                .build();
    }

    @Override
    public List<SegmentKpiDto> computeSegmentKpis(RoadNetwork network, long currentTick) {
        List<SegmentKpiDto> out = new ArrayList<>();
        if (network == null || network.getRoads() == null) {
            return out;
        }
        for (Road road : network.getRoads().values()) {
            out.add(computeOneSegmentKpi(road));
        }
        return out;
    }

    private SegmentKpiDto computeOneSegmentKpi(Road road) {
        int vehicleCount = 0;
        double totalSpeed = 0.0;
        double maxQueue = 0.0;
        int laneCount = road.getLanes() == null ? 0 : road.getLanes().size();
        if (road.getLanes() != null) {
            for (Lane lane : road.getLanes()) {
                for (Vehicle v : lane.getVehiclesView()) {
                    vehicleCount++;
                    totalSpeed += v.getSpeed();
                }
                double q = QueueAnalyzer.maxQueueLengthMeters(lane, road.getSpeedLimit());
                if (q > maxQueue) {
                    maxQueue = q;
                }
            }
        }
        double densityPerKm = (vehicleCount * 1000.0) / Math.max(1.0, road.getLength());
        double densityPerKmPerLane = densityPerKm / Math.max(1, laneCount);
        double meanSpeedMps = vehicleCount > 0 ? totalSpeed / vehicleCount : 0.0;
        // flow (veh/min) = density (veh/km) × speed (m/s) × 60 / 1000
        double flowPerMin = densityPerKm * meanSpeedMps * 60.0 / 1000.0;
        return SegmentKpiDto.builder()
                .roadId(road.getId())
                .densityPerKm(densityPerKm)
                .flowVehiclesPerMin(flowPerMin)
                .meanSpeedMps(meanSpeedMps)
                .p95QueueLengthMeters(maxQueue)
                .los(LosClassifier.classify(densityPerKmPerLane))
                .build();
    }

    @Override
    public List<IntersectionKpiDto> computeIntersectionKpis(
            RoadNetwork network, long currentTick) {
        List<IntersectionKpiDto> out = new ArrayList<>();
        if (network == null || network.getIntersections() == null) {
            return out;
        }
        for (Intersection ixtn : network.getIntersections().values()) {
            double maxQueue = 0.0;
            String worstLos = "A";
            if (ixtn.getInboundRoadIds() != null) {
                for (String inRoadId : ixtn.getInboundRoadIds()) {
                    Road inRoad = network.getRoads().get(inRoadId);
                    if (inRoad == null) {
                        continue;
                    }
                    int laneCount = inRoad.getLanes() == null ? 0 : inRoad.getLanes().size();
                    int vehicleCount = 0;
                    if (inRoad.getLanes() != null) {
                        for (Lane lane : inRoad.getLanes()) {
                            vehicleCount += lane.getVehiclesView().size();
                            double q =
                                    QueueAnalyzer.maxQueueLengthMeters(lane, inRoad.getSpeedLimit());
                            if (q > maxQueue) {
                                maxQueue = q;
                            }
                        }
                    }
                    double densityPerKmPerLane =
                            (vehicleCount * 1000.0)
                                    / Math.max(1.0, inRoad.getLength())
                                    / Math.max(1, laneCount);
                    worstLos =
                            LosClassifier.worse(
                                    worstLos, LosClassifier.classify(densityPerKmPerLane));
                }
            }
            out.add(
                    IntersectionKpiDto.builder()
                            .intersectionId(ixtn.getId())
                            .inboundQueueLengthMeters(maxQueue)
                            .worstLos(worstLos)
                            .build());
        }
        return out;
    }
}
