package com.trafficsimulator.model;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrafficLight {
    private String intersectionId;
    private List<TrafficLightPhase> phases;
    private int currentPhaseIndex;
    private long phaseElapsedMs;

    /**
     * Advances the traffic light by dt seconds. When phaseElapsedMs exceeds current phase duration,
     * advances to next phase (wraps around).
     */
    public void tick(double dtSeconds) {
        long dtMs = (long) (dtSeconds * 1000.0);
        phaseElapsedMs += dtMs;
        while (phaseElapsedMs >= getCurrentPhase().getDurationMs()) {
            phaseElapsedMs -= getCurrentPhase().getDurationMs();
            currentPhaseIndex = (currentPhaseIndex + 1) % phases.size();
        }
    }

    public TrafficLightPhase getCurrentPhase() {
        return phases.get(currentPhaseIndex);
    }

    /** Returns true if the given inbound road currently has a green signal. */
    public boolean isGreen(String inboundRoadId) {
        TrafficLightPhase phase = getCurrentPhase();
        return phase.getType() == TrafficLightPhase.PhaseType.GREEN
                && phase.getGreenRoadIds().contains(inboundRoadId);
    }

    /** Returns true if the given inbound road currently has a yellow signal. */
    public boolean isYellow(String inboundRoadId) {
        TrafficLightPhase phase = getCurrentPhase();
        return phase.getType() == TrafficLightPhase.PhaseType.YELLOW
                && phase.getGreenRoadIds().contains(inboundRoadId);
    }

    /** Returns the signal state for a given inbound road: "GREEN", "YELLOW", or "RED". */
    public String getSignalState(String inboundRoadId) {
        if (isGreen(inboundRoadId)) {
            return "GREEN";
        }
        if (isYellow(inboundRoadId)) {
            return "YELLOW";
        }
        return "RED";
    }

    /** Replaces phases and resets timer. Used by SET_LIGHT_CYCLE command. */
    public void replacePhases(List<TrafficLightPhase> newPhases) {
        this.phases = newPhases;
        this.phaseElapsedMs = 0;
        if (this.currentPhaseIndex >= newPhases.size()) {
            this.currentPhaseIndex = 0;
        }
    }
}
