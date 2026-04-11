package com.trafficsimulator.engine;

import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.Road;

/** Static utility methods for intersection geometry and math calculations. */
final class IntersectionGeometry {

    static final double STOP_LINE_BUFFER_DEFAULT = 2.0; // metres before road end (fallback)

    private IntersectionGeometry() {}

    /**
     * Computes stop line buffer in domain units based on the intersection's pixel size and the
     * road's pixel-to-domain ratio. This ensures vehicles stop at the visual edge of the
     * intersection box, not inside it.
     */
    static double computeStopLineBuffer(Intersection ixtn, Road road) {
        if (ixtn.getIntersectionSize() <= 0) {
            return STOP_LINE_BUFFER_DEFAULT;
        }
        double halfSizePx = ixtn.getIntersectionSize() / 2.0;
        double pixelLength =
                Math.sqrt(
                        Math.pow(road.getEndX() - road.getStartX(), 2)
                                + Math.pow(road.getEndY() - road.getStartY(), 2));
        if (pixelLength < 1) {
            return STOP_LINE_BUFFER_DEFAULT;
        }
        // Convert pixel half-size to domain units using the road's scale
        return halfSizePx * (road.getLength() / pixelLength) + 1.0; // +1m margin
    }

    static double normalizeAngle(double angle) {
        double result = angle;
        while (result > Math.PI) {
            result -= 2 * Math.PI;
        }
        while (result <= -Math.PI) {
            result += 2 * Math.PI;
        }
        return result;
    }

    /**
     * Simple heuristic: "r_north_in" -> "r_north_out" is a U-turn. Replace "_in" with "_out" to
     * guess the reverse road ID. Not all maps follow this convention, so this is best-effort.
     */
    static String reverseRoadId(String inboundRoadId) {
        return inboundRoadId.replace("_in", "_out");
    }

    /**
     * Returns true if otherAngle is approximately 90 degrees clockwise from myAngle (i.e., the
     * other road approaches from the right).
     */
    static boolean isApproachFromRight(double myAngle, double otherAngle) {
        double diff = normalizeAngle(otherAngle - myAngle);
        // diff around -PI/2 means from the right
        return diff > -Math.PI * 0.75 && diff < -Math.PI * 0.25;
    }
}
