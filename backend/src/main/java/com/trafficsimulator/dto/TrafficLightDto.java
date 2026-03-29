package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficLightDto {
    private String intersectionId;
    private String roadId;         // which inbound road this light serves
    private String state;          // "GREEN", "YELLOW", "RED"
    private double x;              // pixel x at stop line
    private double y;              // pixel y at stop line
    private double angle;          // road direction for orientation
}
