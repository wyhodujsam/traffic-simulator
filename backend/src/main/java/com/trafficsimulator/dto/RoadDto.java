package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoadDto {
    private String id;
    private String name;
    private int laneCount;
    private double length;       // metres
    private double speedLimit;   // m/s
    private double startX;       // pixel X of road start
    private double startY;       // pixel Y of road start
    private double endX;         // pixel X of road end
    private double endY;         // pixel Y of road end
}
