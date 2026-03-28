package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObstacleDto {
    private String id;
    private String laneId;
    private double position;  // metres from lane start
    private double x;         // pixel x coordinate
    private double y;         // pixel y coordinate
    private double angle;     // road angle (radians, for rendering rotation)
}
