package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-segment KPI (D-08). Sub-sampled every 5 ticks. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentKpiDto {
    private String roadId;
    private double densityPerKm;
    private double flowVehiclesPerMin;
    private double meanSpeedMps;
    private double p95QueueLengthMeters;
    private String los;
}
