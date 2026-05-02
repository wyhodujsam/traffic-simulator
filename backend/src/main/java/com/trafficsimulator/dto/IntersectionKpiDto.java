package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-intersection KPI (D-08). Sub-sampled every 5 ticks. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntersectionKpiDto {
    private String intersectionId;
    private double inboundQueueLengthMeters;
    private String worstLos;
}
