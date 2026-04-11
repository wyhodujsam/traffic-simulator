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
public class SimulationStateDto {
    private long tick;
    private long timestamp;
    private String status; // "RUNNING", "PAUSED", "STOPPED"
    private List<VehicleDto> vehicles;
    private List<ObstacleDto> obstacles;
    private List<TrafficLightDto> trafficLights;
    private StatsDto stats;
    private String mapId;
    private String error;
}
