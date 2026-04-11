package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationStatusDto {
    private String status; // "RUNNING", "PAUSED", "STOPPED"
    private long tick;
    private int vehicleCount;
    private double speedMultiplier;
    private double spawnRate;
    private String mapId; // currently loaded map ID, or null
    private double maxSpeed; // global max speed in m/s
}
