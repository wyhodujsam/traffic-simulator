package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.trafficsimulator.config.MapConfig.IntersectionConfig;

/**
 * 4-arm roundabout with a CCW ring (mirrors {@code roundabout.json}).
 *
 * <p>Always emits all 4 ring nodes + 4 ring roads + 4 ROUNDABOUT intersections, regardless of
 * which arms are present — the ring is structural. For each arm in {@code armsPresent} an
 * ENTRY+EXIT pair, an {@code _in} approach road, an {@code _out} departure road, plus
 * spawn/despawn points are emitted.
 *
 * @param id          component id (must be lowercase alphanumeric, no {@code in}/{@code out}).
 * @param center      world-pixel centre of the ring.
 * @param rotationDeg clockwise rotation about {@code center} in degrees.
 * @param armsPresent subset of {@code [north, east, south, west]}; order ignored, duplicates rejected.
 */
public record RoundaboutFourArm(
        String id, Point2D.Double center, double rotationDeg, List<String> armsPresent)
        implements ComponentSpec {

    public static final double RING_R = 28.0;
    public static final double APPROACH_LEN = 200.0;
    public static final double APPROACH_SPEED = 11.1;
    public static final double RING_SPEED = 8.3;
    public static final double RING_ROAD_LEN = 22.0;
    public static final double RING_INTERSECTION_SIZE = 12.0;
    public static final int RING_CAPACITY = 8;

    private static final List<String> ALL_ARMS = List.of("north", "east", "south", "west");
    /** Base angle (degrees, screen-space CW from +X axis) for each compass arm. */
    private static final Map<String, Double> ARM_ANGLE_DEG =
            Map.of("north", 270.0, "east", 0.0, "south", 90.0, "west", 180.0);
    /** CCW ring traversal: from → to. */
    private static final List<String[]> RING_EDGES =
            List.of(
                    new String[] {"north", "west"},
                    new String[] {"west", "south"},
                    new String[] {"south", "east"},
                    new String[] {"east", "north"});

    public RoundaboutFourArm {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(armsPresent, "armsPresent");
        if (armsPresent.isEmpty()) {
            throw new IllegalArgumentException("RoundaboutFourArm requires at least one arm");
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
        // Always emit the 4 ring nodes (structural).
        for (String arm : ALL_ARMS) {
            Point2D.Double ringPt = ringNode(arm);
            ctx.addNode(ctx.prefix(id, "n_ring_" + initial(arm)), "INTERSECTION", ringPt.x, ringPt.y);
        }

        // Emit per-arm ENTRY/EXIT, approach/departure road, spawn/despawn.
        for (String arm : armsPresent) {
            Point2D.Double endPt = armEndpoint(arm);
            String entryId = ctx.prefix(id, "n_" + arm);
            String exitId = ctx.prefix(id, "n_" + arm + "_exit");
            ctx.addNode(entryId, "ENTRY", endPt.x, endPt.y);
            ctx.addNode(exitId, "EXIT", endPt.x, endPt.y);

            String inRoad = ctx.prefix(id, "r_" + arm + "_in");
            String outRoad = ctx.prefix(id, "r_" + arm + "_out");
            String ringNodeId = ctx.prefix(id, "n_ring_" + initial(arm));
            ctx.addRoad(inRoad, capitalise(arm) + " Approach", entryId, ringNodeId,
                    APPROACH_LEN, APPROACH_SPEED, 1);
            ctx.addRoad(outRoad, capitalise(arm) + " Departure", ringNodeId, exitId,
                    APPROACH_LEN, APPROACH_SPEED, 1);
            ctx.addSpawn(inRoad, 0, 0.0);
            ctx.addDespawn(outRoad, 0, APPROACH_LEN);
            ctx.registerArm(id, arm, entryId, exitId, endPt);
        }

        // Always emit the 4 ring roads CCW.
        for (String[] edge : RING_EDGES) {
            String from = edge[0];
            String to = edge[1];
            String roadId = ctx.prefix(id, "r_ring_" + initial(from) + initial(to));
            String fromNode = ctx.prefix(id, "n_ring_" + initial(from));
            String toNode = ctx.prefix(id, "n_ring_" + initial(to));
            ctx.addRoad(roadId, "Ring " + initial(from).toUpperCase() + "→" + initial(to).toUpperCase(),
                    fromNode, toNode, RING_ROAD_LEN, RING_SPEED, 1);
        }

        // 4 ROUNDABOUT intersections, one per ring node.
        for (String arm : ALL_ARMS) {
            IntersectionConfig ic = new IntersectionConfig();
            ic.setNodeId(ctx.prefix(id, "n_ring_" + initial(arm)));
            ic.setType("ROUNDABOUT");
            ic.setIntersectionSize(RING_INTERSECTION_SIZE);
            ic.setRoundaboutCapacity(RING_CAPACITY);
            ctx.intersections.add(ic);
        }
    }

    @Override
    public Map<String, Point2D.Double> armEndpoints() {
        Map<String, Point2D.Double> out = new LinkedHashMap<>();
        for (String arm : armsPresent) {
            out.put(arm, armEndpoint(arm));
        }
        return out;
    }

    private Point2D.Double ringNode(String arm) {
        double angleRad = Math.toRadians(ARM_ANGLE_DEG.get(arm) + rotationDeg);
        return new Point2D.Double(
                center.x + Math.cos(angleRad) * RING_R, center.y + Math.sin(angleRad) * RING_R);
    }

    private Point2D.Double armEndpoint(String arm) {
        double angleRad = Math.toRadians(ARM_ANGLE_DEG.get(arm) + rotationDeg);
        double r = RING_R + APPROACH_LEN;
        return new Point2D.Double(center.x + Math.cos(angleRad) * r, center.y + Math.sin(angleRad) * r);
    }

    private static String initial(String arm) {
        return arm.substring(0, 1);
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
