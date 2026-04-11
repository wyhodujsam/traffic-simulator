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
    private String roadId; // which road
    private String laneId;
    private int laneIndex; // 0-based lane index
    private double position; // metres from lane start
    private double speed; // m/s
    private double laneChangeProgress; // 0.0..1.0
    private int laneChangeSourceIndex; // -1 = none
}
