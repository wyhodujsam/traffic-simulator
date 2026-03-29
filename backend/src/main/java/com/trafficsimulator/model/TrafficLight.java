package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Traffic light associated with an intersection.
 * Manages green phases that cycle through inbound road groups.
 */
@Data
@Builder
public class TrafficLight {
    private String id;

    @Builder.Default
    private List<TrafficLightPhase> phases = List.of();

    @Builder.Default
    private int currentPhaseIndex = 0;

    @Builder.Default
    private double elapsedInPhase = 0.0;

    /**
     * Checks if the given inbound road currently has a green signal.
     */
    public boolean isGreen(String inboundRoadId) {
        if (phases.isEmpty()) return true; // no phases = always green
        TrafficLightPhase current = phases.get(currentPhaseIndex);
        return current.getGreenRoadIds().contains(inboundRoadId);
    }

    /**
     * Advances the traffic light by dt seconds, cycling phases as needed.
     */
    public void advance(double dt) {
        if (phases.isEmpty()) return;
        elapsedInPhase += dt;
        TrafficLightPhase current = phases.get(currentPhaseIndex);
        while (elapsedInPhase >= current.getDuration()) {
            elapsedInPhase -= current.getDuration();
            currentPhaseIndex = (currentPhaseIndex + 1) % phases.size();
            current = phases.get(currentPhaseIndex);
        }
    }
}
