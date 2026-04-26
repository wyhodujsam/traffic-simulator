package com.trafficsimulator.engine.kpi;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.stereotype.Component;

/**
 * 60-second rolling window of {@code (despawnTick, delaySeconds)} samples per CONTEXT.md §D-05.
 *
 * <p>Tick-keyed (NOT wall-clock) so DET-01 byte-identity holds — same pattern as {@link
 * com.trafficsimulator.engine.VehicleSpawner#getThroughput(long)} (Plan 01 precedent).
 *
 * <p>Window is 60 simulated seconds = 1200 ticks at 20 Hz.
 */
@Component
public class DelayWindow {

    public static final int TICKS_PER_SEC = 20;
    public static final long WINDOW_TICKS = 60L * TICKS_PER_SEC; // = 1200

    private record Sample(long tick, double delaySeconds) {}

    private final Deque<Sample> window = new ArrayDeque<>();

    /** Records a despawn event with its measured delay (actual − free-flow). */
    public void recordDespawn(long tick, double delaySeconds) {
        window.addLast(new Sample(tick, delaySeconds));
    }

    /**
     * Returns the mean delay in seconds across all samples currently inside the rolling window
     * {@code [currentTick − WINDOW_TICKS, currentTick]}. Evicts stale samples as a side-effect.
     *
     * @param currentTick the simulation's current tick number
     * @return mean delay in seconds, or 0.0 when the window is empty
     */
    public double meanDelay(long currentTick) {
        evict(currentTick);
        if (window.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Sample s : window) {
            sum += s.delaySeconds();
        }
        return sum / window.size();
    }

    /** Returns the number of samples currently inside the rolling window. */
    public int size(long currentTick) {
        evict(currentTick);
        return window.size();
    }

    /** Drops every sample (used by {@code Stop} / {@code LoadMap} / {@code LoadConfig}). */
    public void reset() {
        window.clear();
    }

    private void evict(long currentTick) {
        long cutoff = currentTick - WINDOW_TICKS;
        while (!window.isEmpty() && window.peekFirst().tick() < cutoff) {
            window.pollFirst();
        }
    }
}
