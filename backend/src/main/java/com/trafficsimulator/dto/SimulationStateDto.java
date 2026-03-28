package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationStateDto {
    private long tick;
    private long timestamp;
    private String status;            // "RUNNING", "PAUSED", "STOPPED"
    private List<VehicleDto> vehicles;
    private List<ObstacleDto> obstacles;
    private StatsDto stats;
}
