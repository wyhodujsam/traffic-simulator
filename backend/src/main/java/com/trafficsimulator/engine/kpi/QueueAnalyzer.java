package com.trafficsimulator.engine.kpi;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Vehicle;

/**
 * Per-lane queue-length analysis per CONTEXT.md §D-06.
 *
 * <p>A queue is the maximum contiguous run of vehicles whose speed is below {@code 0.30 ×
 * speedLimit}, measured from the segment exit going upstream, expressed in metres. If the
 * vehicle closest to the exit is not queued, the lane has no queue at the exit (returns 0).
 */
public final class QueueAnalyzer {

    /** Per CONTEXT.md §D-06: queue threshold is 30% of the segment's speed limit. */
    public static final double QUEUE_SPEED_THRESHOLD_FACTOR = 0.30;

    private QueueAnalyzer() {}

    /**
     * Returns the maximum queue length in metres starting at the lane exit and going upstream.
     *
     * <p>{@link Lane#getVehiclesView()} returns vehicles sorted descending by position (closest to
     * exit first — see {@link Lane#addVehicle}). The queue starts at the first (head) vehicle if it
     * is below the threshold; the queue ends at the first non-queued vehicle behind it. Length =
     * head.position − tail.position.
     *
     * @param lane the lane to analyse (may be {@code null})
     * @param speedLimit the segment's speed limit in m/s
     * @return queue length in metres, or 0 if no queue starts at the exit
     */
    public static double maxQueueLengthMeters(Lane lane, double speedLimit) {
        if (lane == null || lane.getVehiclesView().isEmpty()) {
            return 0.0;
        }
        double threshold = QUEUE_SPEED_THRESHOLD_FACTOR * speedLimit;

        double queueStart = -1.0;
        double queueEnd = -1.0;
        boolean inQueue = false;
        for (Vehicle v : lane.getVehiclesView()) {
            if (v.getSpeed() < threshold) {
                if (!inQueue) {
                    queueStart = v.getPosition();
                    inQueue = true;
                }
                queueEnd = v.getPosition();
            } else {
                if (inQueue) {
                    break; // queue ends; do not look further upstream
                }
                // Not yet in queue and head vehicle is fast — D-06 says measure from exit only.
                return 0.0;
            }
        }
        return inQueue ? Math.max(0.0, queueStart - queueEnd) : 0.0;
    }
}
