package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Obstacle {
    private String id;          // UUID
    private String laneId;      // lane this obstacle sits on
    private double position;    // metres from lane start
    private double length;      // metres (default 3.0 — blocks ~1 car length)
    private long createdAtTick; // tick when placed
}
