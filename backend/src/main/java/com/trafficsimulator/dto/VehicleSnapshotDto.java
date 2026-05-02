package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Narrow vehicle snapshot for replay NDJSON. Per CONTEXT.md §D-14: id, roadId, laneIndex, position,
 * speed only — omits laneId / laneChange fields not needed for byte-identity replay verification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleSnapshotDto {
    private String id;
    private String roadId;
    private int laneIndex;
    private double position;
    private double speed;
}
