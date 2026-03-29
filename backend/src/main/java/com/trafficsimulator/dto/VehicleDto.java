package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleDto {
    private String id;
    private String laneId;
    private double position;   // metres from lane start
    private double speed;      // m/s
    private double x;          // pixel x coordinate for Canvas
    private double y;          // pixel y coordinate for Canvas
    private double angle;      // radians — road direction for vehicle rotation
    private String targetLaneId;        // null if not mid-transition
    private double laneChangeProgress;  // 0.0 = just changed, 1.0 = settled
}
