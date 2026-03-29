package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;
import java.util.Set;

@Data
@Builder
public class TrafficLightPhase {
    private Set<String> greenRoadIds;  // inbound road IDs that get green in this phase
    private long durationMs;           // how long this phase lasts
    private PhaseType type;            // GREEN, YELLOW, ALL_RED

    public enum PhaseType {
        GREEN, YELLOW, ALL_RED
    }
}
