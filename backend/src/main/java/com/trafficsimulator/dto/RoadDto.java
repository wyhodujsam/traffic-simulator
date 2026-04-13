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
public class RoadDto {
    private String id;
    private String name;
    private int laneCount;
    private double length; // metres
    private double speedLimit; // m/s
    private double startX; // pixel X of road start
    private double startY; // pixel Y of road start
    private double endX; // pixel X of road end
    private double endY; // pixel Y of road end
    private List<LaneDto> lanes; // per-lane active status for frontend
    private double clipStart; // pixels to trim from road start (near intersection)
    private double clipEnd; // pixels to trim from road end (near intersection)
    private double lateralOffset; // perpendicular render shift (backend coords)
}
