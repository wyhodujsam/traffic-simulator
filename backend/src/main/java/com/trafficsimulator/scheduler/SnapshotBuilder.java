package com.trafficsimulator.scheduler;

import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.trafficsimulator.dto.IntersectionKpiDto;
import com.trafficsimulator.dto.KpiDto;
import com.trafficsimulator.dto.ObstacleDto;
import com.trafficsimulator.dto.SegmentKpiDto;
import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.dto.StatsDto;
import com.trafficsimulator.dto.TrafficLightDto;
import com.trafficsimulator.dto.VehicleDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.engine.kpi.IKpiAggregator;
import com.trafficsimulator.engine.kpi.KpiCacheInvalidator;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.Vehicle;

@Component
public class SnapshotBuilder implements KpiCacheInvalidator {

    /** Per CONTEXT.md §D-08: per-segment / per-intersection KPI lists are sampled every N ticks. */
    static final int KPI_LIST_SUBSAMPLE_TICKS = 5;

    /**
     * Phase 25 KPI compute (D-08). Optional — null in unit tests that don't exercise KPI paths.
     * Stored as a non-final field so tests can swap a spy via {@link
     * org.springframework.test.util.ReflectionTestUtils#setField}; ArchUnit forbids @Autowired
     * field injection so the field is wired through the constructor.
     */
    @Nullable private IKpiAggregator kpiAggregator;

    /** Cached per-segment KPI list — refreshed every {@link #KPI_LIST_SUBSAMPLE_TICKS} ticks. */
    private List<SegmentKpiDto> lastSegmentKpis = List.of();

    /**
     * Cached per-intersection KPI list — refreshed every {@link #KPI_LIST_SUBSAMPLE_TICKS} ticks.
     */
    private List<IntersectionKpiDto> lastIntersectionKpis = List.of();

    /** Default constructor for tests / non-Spring callers — no KPI compute. */
    public SnapshotBuilder() {
        this(null);
    }

    /**
     * Spring-preferred constructor — receives optional {@link IKpiAggregator} via DI.
     * {@code @Autowired} required to disambiguate from the no-arg constructor; without it Spring
     * defaults to the lowest-arity constructor and {@code kpiAggregator} stays null at runtime
     * (KPI-06 regression: stats.kpi was never populated on the wire).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SnapshotBuilder(@Nullable IKpiAggregator kpiAggregator) {
        this.kpiAggregator = kpiAggregator;
    }

    /**
     * Drops the KPI sub-sampling cache. Called from {@code CommandDispatcher} on {@code LoadMap} /
     * {@code LoadConfig} per KPI-07 so a fresh map starts with empty per-segment KPIs until the
     * next sub-sampling tick. Implements {@link KpiCacheInvalidator} so the engine layer can
     * invalidate the cache without importing the scheduler layer.
     */
    @Override
    public void clearCache() {
        lastSegmentKpis = List.of();
        lastIntersectionKpis = List.of();
    }

    /** Groups the parameters needed to build a simulation snapshot. */
    public record SnapshotConfig(
            RoadNetwork network,
            long tick,
            String status,
            IVehicleSpawner vehicleSpawner,
            String mapId,
            String error) {}

    private record VehicleObstacleCollection(
            List<VehicleDto> vehicles,
            List<ObstacleDto> obstacles,
            double totalSpeed,
            int vehicleCount,
            double totalRoadLength) {}

    /** Builds a complete SimulationStateDto snapshot from the current state. */
    public SimulationStateDto buildSnapshot(SnapshotConfig config) {
        return buildSnapshot(
                config.network(),
                config.tick(),
                config.status(),
                config.vehicleSpawner(),
                config.mapId(),
                config.error());
    }

    /** Builds a complete SimulationStateDto snapshot from the current state. */
    public SimulationStateDto buildSnapshot(
            RoadNetwork network,
            long tick,
            String status,
            IVehicleSpawner vehicleSpawner,
            String mapId,
            String error) {

        VehicleObstacleCollection collection = collectVehiclesAndObstacles(network);
        List<TrafficLightDto> trafficLights = collectTrafficLights(network);
        StatsDto stats = computeStats(collection, vehicleSpawner, tick, network);

        return SimulationStateDto.builder()
                .tick(tick)
                .timestamp(System.currentTimeMillis())
                .status(status)
                .vehicles(collection.vehicles())
                .obstacles(collection.obstacles())
                .trafficLights(trafficLights)
                .stats(stats)
                .mapId(mapId)
                .error(error)
                .build();
    }

    private VehicleObstacleCollection collectVehiclesAndObstacles(RoadNetwork network) {
        List<VehicleDto> vehicleDtos = new ArrayList<>();
        List<ObstacleDto> obstacleDtos = new ArrayList<>();
        double totalSpeed = 0.0;
        int vehicleCount = 0;
        double totalRoadLength = 0.0;

        if (network == null) {
            return new VehicleObstacleCollection(
                    vehicleDtos, obstacleDtos, totalSpeed, vehicleCount, totalRoadLength);
        }

        for (Road road : network.getRoads().values()) {
            totalRoadLength += road.getLength();
            for (int laneIdx = 0; laneIdx < road.getLanes().size(); laneIdx++) {
                Lane lane = road.getLanes().get(laneIdx);
                for (Vehicle v : lane.getVehiclesView()) {
                    vehicleDtos.add(buildVehicleDto(v, road, laneIdx));
                    totalSpeed += v.getSpeed();
                    vehicleCount++;
                }
                for (Obstacle obs : lane.getObstaclesView()) {
                    obstacleDtos.add(buildObstacleDto(obs, road, laneIdx));
                }
            }
        }

        return new VehicleObstacleCollection(
                vehicleDtos, obstacleDtos, totalSpeed, vehicleCount, totalRoadLength);
    }

    private List<TrafficLightDto> collectTrafficLights(RoadNetwork network) {
        List<TrafficLightDto> trafficLightDtos = new ArrayList<>();
        if (network == null) {
            return trafficLightDtos;
        }

        for (Intersection ixtn : network.getIntersections().values()) {
            if (ixtn.getTrafficLight() == null) {
                continue;
            }
            TrafficLight tl = ixtn.getTrafficLight();
            for (String inRoadId : ixtn.getInboundRoadIds()) {
                Road inRoad = network.getRoads().get(inRoadId);
                if (inRoad == null) {
                    continue;
                }
                String signalState = tl.getSignalState(inRoadId);
                boolean boxBlocked =
                        "GREEN".equals(signalState) && isBoxBlocked(ixtn, inRoadId, network);
                trafficLightDtos.add(
                        TrafficLightDto.builder()
                                .intersectionId(ixtn.getId())
                                .roadId(inRoadId)
                                .state(signalState)
                                .x(inRoad.getEndX())
                                .y(inRoad.getEndY())
                                .angle(
                                        Math.atan2(
                                                inRoad.getEndY() - inRoad.getStartY(),
                                                inRoad.getEndX() - inRoad.getStartX()))
                                .boxBlocked(boxBlocked)
                                .build());
            }
        }
        return trafficLightDtos;
    }

    private boolean isBoxBlocked(Intersection ixtn, String inRoadId, RoadNetwork network) {
        for (String outRoadId : ixtn.getOutboundRoadIds()) {
            if (hasSpaceOnOutbound(outRoadId, inRoadId, network)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSpaceOnOutbound(String outRoadId, String inRoadId, RoadNetwork network) {
        if (outRoadId.replace("_in", "_out").equals(inRoadId.replace("_in", "_out"))) {
            return false;
        }
        Road outRoad = network.getRoads().get(outRoadId);
        if (outRoad == null) {
            return false;
        }
        for (Lane outLane : outRoad.getLanes()) {
            if (outLane.isActive()
                    && (outLane.getVehiclesView().isEmpty()
                            || outLane.getVehiclesView().get(0).getPosition() > 10.0)) {
                return true;
            }
        }
        return false;
    }

    private StatsDto computeStats(
            VehicleObstacleCollection collection,
            IVehicleSpawner vehicleSpawner,
            long currentTick,
            RoadNetwork network) {
        int vehicleCount = collection.vehicleCount();
        double avgSpeed = vehicleCount > 0 ? collection.totalSpeed() / vehicleCount : 0.0;
        double density =
                collection.totalRoadLength() > 0
                        ? (vehicleCount / (collection.totalRoadLength() / 1000.0))
                        : 0.0;

        // Phase 25 D-08: network KPI cheap (every tick); per-segment / per-intersection
        // sub-sampled every KPI_LIST_SUBSAMPLE_TICKS ticks and reused from cache in between.
        KpiDto kpi = null;
        if (kpiAggregator != null) {
            kpi = kpiAggregator.computeNetworkKpi(network, currentTick, vehicleSpawner);
            // D-08 sub-sampling: equivalent to currentTick % 5 == 0 (constant lifted for
            // testability).
            if (currentTick % KPI_LIST_SUBSAMPLE_TICKS == 0) {
                lastSegmentKpis = kpiAggregator.computeSegmentKpis(network, currentTick);
                lastIntersectionKpis = kpiAggregator.computeIntersectionKpis(network, currentTick);
            }
        }

        return StatsDto.builder()
                .vehicleCount(vehicleCount)
                .avgSpeed(avgSpeed)
                .density(density)
                .throughput(vehicleSpawner.getThroughput(currentTick))
                .kpi(kpi)
                .segmentKpis(lastSegmentKpis)
                .intersectionKpis(lastIntersectionKpis)
                .build();
    }

    /** Maps a domain Vehicle to a VehicleDto with domain coordinates. */
    VehicleDto buildVehicleDto(Vehicle v, Road road, int laneIndex) {
        return VehicleDto.builder()
                .id(v.getId())
                .roadId(road.getId())
                .laneId(v.getLane().getId())
                .laneIndex(laneIndex)
                .position(v.getPosition())
                .speed(v.getSpeed())
                .laneChangeProgress(v.getLaneChangeProgress())
                .laneChangeSourceIndex(v.getLaneChangeSourceIndex())
                .build();
    }

    /** Maps a domain Obstacle to an ObstacleDto with domain coordinates. */
    ObstacleDto buildObstacleDto(Obstacle obs, Road road, int laneIndex) {
        return ObstacleDto.builder()
                .id(obs.getId())
                .roadId(road.getId())
                .laneId(obs.getLaneId())
                .laneIndex(laneIndex)
                .position(obs.getPosition())
                .build();
    }
}
