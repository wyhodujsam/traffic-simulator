package com.trafficsimulator.engine.kpi;

import java.util.List;

import com.trafficsimulator.dto.IntersectionKpiDto;
import com.trafficsimulator.dto.KpiDto;
import com.trafficsimulator.dto.SegmentKpiDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.model.RoadNetwork;

/**
 * Computes the Phase 25 KPI suite (D-05/06/07/08). Implementation owned by {@link KpiAggregator};
 * interface kept for testability — the {@code IXxx + XxxImpl} pattern matches existing engine
 * abstractions ({@code IPhysicsEngine}, {@code IVehicleSpawner}).
 */
public interface IKpiAggregator {

    /** Returns the network-level KPI block (cheap — recomputed every tick). */
    KpiDto computeNetworkKpi(RoadNetwork network, long currentTick, IVehicleSpawner spawner);

    /** Returns the per-segment KPI list (sub-sampled in {@code SnapshotBuilder} per D-08). */
    List<SegmentKpiDto> computeSegmentKpis(RoadNetwork network, long currentTick);

    /** Returns the per-intersection KPI list (sub-sampled in {@code SnapshotBuilder} per D-08). */
    List<IntersectionKpiDto> computeIntersectionKpis(RoadNetwork network, long currentTick);
}
