package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Network-level KPI (D-08). Broadcast every tick on /topic/state. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiDto {
    private double throughputVehiclesPerMin;
    private double meanDelaySeconds;
    private double p95QueueLengthMeters;
    /** "A".."F" — worst LOS across all segments. */
    private String worstLos;
}
