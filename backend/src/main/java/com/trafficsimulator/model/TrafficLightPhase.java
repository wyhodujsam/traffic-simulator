package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A single phase in a traffic light cycle.
 * Specifies which inbound roads have green and how long the phase lasts.
 */
@Data
@Builder
public class TrafficLightPhase {
    private List<String> greenRoadIds;  // inbound road IDs that are green during this phase
    private double duration;             // seconds
}
