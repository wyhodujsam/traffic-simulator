package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.trafficsimulator.config.MapConfig.IntersectionConfig;
import com.trafficsimulator.config.MapConfig.SignalPhaseConfig;

/**
 * 4-way signalised intersection (mirrors {@code four-way-signal.json}). Single centre INTERSECTION
 * node, per-arm ENTRY/EXIT, {@code _in}/{@code _out} roads, and a 6-phase signal cycle (NS green
 * 25s, NS yellow 3s, ALL_RED 2s, EW green 25s, EW yellow 3s, ALL_RED 2s).
 */
public record SignalFourWay(
        String id, Point2D.Double center, double rotationDeg, List<String> armsPresent)
        implements ComponentSpec {

    public static final double APPROACH_LEN = 280.0;
    public static final double APPROACH_SPEED = 13.9;
    public static final double INTERSECTION_SIZE = 40.0;

    private static final List<String> ALL_ARMS = List.of("north", "east", "south", "west");
    private static final Map<String, Double> ARM_ANGLE_DEG =
            Map.of("north", 270.0, "east", 0.0, "south", 90.0, "west", 180.0);

    public SignalFourWay {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(armsPresent, "armsPresent");
        if (armsPresent.size() != 4 || !armsPresent.containsAll(ALL_ARMS)) {
            throw new IllegalArgumentException(
                    "SignalFourWay requires all 4 arms (north, east, south, west); got "
                            + armsPresent);
        }
    }

    @Override
    public void expand(ExpansionContext ctx) {
        String centerId = ctx.prefix(id, "n_center");
        ctx.addNode(centerId, "INTERSECTION", center.x, center.y);

        for (String arm : ALL_ARMS) {
            Point2D.Double endPt = armEndpoint(arm);
            String entryId = ctx.prefix(id, "n_" + arm);
            String exitId = ctx.prefix(id, "n_" + arm + "_exit");
            ctx.addNode(entryId, "ENTRY", endPt.x, endPt.y);
            ctx.addNode(exitId, "EXIT", endPt.x, endPt.y);

            String inRoad = ctx.prefix(id, "r_" + arm + "_in");
            String outRoad = ctx.prefix(id, "r_" + arm + "_out");
            ctx.addRoad(inRoad, capitalise(arm) + " Inbound", entryId, centerId,
                    APPROACH_LEN, APPROACH_SPEED, 1);
            ctx.addRoad(outRoad, capitalise(arm) + " Outbound", centerId, exitId,
                    APPROACH_LEN, APPROACH_SPEED, 1);
            ctx.addSpawn(inRoad, 0, 0.0);
            ctx.addDespawn(outRoad, 0, APPROACH_LEN);
        }

        IntersectionConfig ic = new IntersectionConfig();
        ic.setNodeId(centerId);
        ic.setType("SIGNAL");
        ic.setIntersectionSize(INTERSECTION_SIZE);
        ic.setSignalPhases(buildPhases(ctx));
        ctx.intersections.add(ic);
    }

    private List<SignalPhaseConfig> buildPhases(ExpansionContext ctx) {
        String nIn = ctx.prefix(id, "r_north_in");
        String sIn = ctx.prefix(id, "r_south_in");
        String wIn = ctx.prefix(id, "r_west_in");
        String eIn = ctx.prefix(id, "r_east_in");
        return List.of(
                phase(List.of(nIn, sIn), 25_000, "GREEN"),
                phase(List.of(nIn, sIn), 3_000, "YELLOW"),
                phase(List.of(), 2_000, "ALL_RED"),
                phase(List.of(wIn, eIn), 25_000, "GREEN"),
                phase(List.of(wIn, eIn), 3_000, "YELLOW"),
                phase(List.of(), 2_000, "ALL_RED"));
    }

    private static SignalPhaseConfig phase(List<String> green, long durationMs, String type) {
        SignalPhaseConfig p = new SignalPhaseConfig();
        p.setGreenRoadIds(green);
        p.setDurationMs(durationMs);
        p.setType(type);
        return p;
    }

    @Override
    public Map<String, Point2D.Double> armEndpoints() {
        Map<String, Point2D.Double> out = new LinkedHashMap<>();
        for (String arm : ALL_ARMS) {
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
