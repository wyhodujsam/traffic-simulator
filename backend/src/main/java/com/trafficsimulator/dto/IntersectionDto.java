package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntersectionDto {
    private String id;
    private String type;       // "SIGNAL", "ROUNDABOUT", "PRIORITY", "NONE"
    private double x;          // center X (from node coordinates)
    private double y;          // center Y (from node coordinates)
    private double size;       // total box size in pixels
}
