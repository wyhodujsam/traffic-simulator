package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.trafficsimulator.config.MapConfig.IntersectionConfig;

/**
 * 3-arm priority intersection. {@code rotationDeg=0} means the stem points south. Caller picks
 * exactly 3 arms from {@code [north, east, south, west]}.
 */
public record TIntersection(
        String id, Point2D.Double center, double rotationDeg, List<String> armsPresent)
        implements ComponentSpec {

    public static final double APPROACH_LEN = 200.0;
    public static final double APPROACH_SPEED = 11.1;
    public static final double INTERSECTION_SIZE = 24.0;

    private static final List<String> ALL_ARMS = List.of("north", "east", "south", "west");
    private static final Map<String, Double> ARM_ANGLE_DEG =
            Map.of("north", 270.0, "east", 0.0, "south", 90.0, "west", 180.0);

    public TIntersection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(armsPresent, "armsPresent");
        if (armsPresent.size() != 3) {
            throw new IllegalArgumentException(
                    "TIntersection requires exactly 3 arms; got " + armsPresent);
        }
        for (String arm : armsPresent) {
            if (!ALL_ARMS.contains(arm)) {
                throw new IllegalArgumentException(
                        "Unknown arm '" + arm + "'; expected one of " + ALL_ARMS);
            }
        }
        if (armsPresent.stream().distinct().count() != armsPresent.size()) {
            throw new IllegalArgumentException("Duplicate arm in " + armsPresent);
        }
    }

    @Override
    public void expand(ExpansionContext ctx) {
        String centerId = ctx.prefix(id, "n_center");
        ctx.addNode(centerId, "INTERSECTION", center.x, center.y);

        for (String arm : armsPresent) {
            Point2D.Double endPt = armEndpoint(arm);
            String entryId = ctx.prefix(id, "n_" + arm);
            String exitId = ctx.prefix(id, "n_" + arm + "_exit");
            ctx.addNode(entryId, "ENTRY", endPt.x, endPt.y);
            ctx.addNode(exitId, "EXIT", endPt.x, endPt.y);

            String inRoad = ctx.prefix(id, "r_" + arm + "_in");
            String outRoad = ctx.prefix(id, "r_" + arm + "_out");
            ctx.addRoad(inRoad, capitalise(arm) + " Approach", entryId, centerId,
                    APPROACH_LEN, APPROACH_SPEED, 1);
            ctx.addRoad(outRoad, capitalise(arm) + " Departure", centerId, exitId,
                    APPROACH_LEN, APPROACH_SPEED, 1);
            ctx.addSpawn(inRoad, 0, 0.0);
            ctx.addDespawn(outRoad, 0, APPROACH_LEN);
        }

        IntersectionConfig ic = new IntersectionConfig();
        ic.setNodeId(centerId);
        ic.setType("PRIORITY");
        ic.setIntersectionSize(INTERSECTION_SIZE);
        ctx.intersections.add(ic);
    }

    @Override
    public Map<String, Point2D.Double> armEndpoints() {
        Map<String, Point2D.Double> out = new LinkedHashMap<>();
        for (String arm : armsPresent) {
            out.put(arm, armEndpoint(arm));
        }
        return out;
    }

    private Point2D.Double armEndpoint(String arm) {
        double angleRad = Math.toRadians(ARM_ANGLE_DEG.get(arm) + rotationDeg);
        return new Point2D.Double(
                center.x + Math.cos(angleRad) * APPROACH_LEN,
                center.y + Math.sin(angleRad) * APPROACH_LEN);
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
