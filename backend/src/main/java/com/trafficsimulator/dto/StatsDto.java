package com.trafficsimulator.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsDto {
    private int vehicleCount;
    private double avgSpeed; // m/s
    private double density; // vehicles/km (across all lanes)
    private double throughput; // vehicles despawned in last 60 seconds
    // Phase 25 Plan 04 D-08: KPI block + sub-sampled per-segment / per-intersection lists.
    private KpiDto kpi;
    private List<SegmentKpiDto> segmentKpis;
    private List<IntersectionKpiDto> intersectionKpis;
}
