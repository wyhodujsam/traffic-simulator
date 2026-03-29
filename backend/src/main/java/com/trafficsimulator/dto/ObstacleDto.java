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
    private String roadId;      // which road
    private String laneId;
    private int laneIndex;      // 0-based lane index
    private double position;    // metres from lane start
}
