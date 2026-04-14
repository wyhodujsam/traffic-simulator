package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.trafficsimulator.config.MapConfig.IntersectionConfig;

/**
 * Highway exit ramp — three arms (upstream highway, downstream continuation, exit ramp) meeting
 * at a single {@code PRIORITY} intersection.
 *
 * <p>Arm convention (before {@code rotationDeg}):
 * <ul>
 *   <li>{@code main_in}  — upstream highway, 0° (east of centre). Incoming traffic.</li>
 *   <li>{@code main_out} — downstream continuation, 180° (west of centre). Outgoing.</li>
 *   <li>{@code ramp_out} — exit ramp, 225° (south-west of centre). Outgoing.</li>
 * </ul>
 *
 * <p>Lane-count defaults follow highway convention: main lanes = 2, ramp = 1.
 *
 * <p><strong>Road-id naming deviation:</strong> this component uses the literal ids
 * {@code r_main_in}, {@code r_main_out}, {@code r_ramp_out} rather than the mechanical
 * {@code r_{arm}_in}/{@code r_{arm}_out} pair convention used by {@code TIntersection}. The arm
 * names themselves encode direction. The {@code IntersectionGeometry.reverseRoadId} contract
 * still holds: {@code r_main_in} flips to the real sibling {@code r_main_out}; {@code r_ramp_out}
 * has no {@code _in} suffix so the reverse-road rule never triggers on it.
 */
public record HighwayExitRamp(String id, Point2D.Double center, double rotationDeg)
        implements ComponentSpec {

    public static final double APPROACH_LEN = 240.0;
    public static final double APPROACH_SPEED = 22.2;
    public static final double INTERSECTION_SIZE = 32.0;

    private static final List<String> ALL_ARMS = List.of("main_in", "main_out", "ramp_out");
    private static final Map<String, Double> ARM_ANGLE_DEG =
            Map.of("main_in", 0.0, "main_out", 180.0, "ramp_out", 225.0);
    private static final Map<String, Integer> ARM_LANE_COUNT =
            Map.of("main_in", 2, "main_out", 2, "ramp_out", 1);
    /**
     * Node id nicknames: node ids must NOT contain {@code _in}/{@code _out} substrings (see
     * {@code IntersectionGeometry.reverseRoadId}). Arm logical names contain those substrings,
     * so we map them to safe short nicknames for node id generation only. Road ids keep the
     * {@code r_main_in}/{@code r_main_out}/{@code r_ramp_out} convention from 22-CONTEXT §3.
     */
    private static final Map<String, String> ARM_NODE_NICK =
            Map.of("main_in", "mi", "main_out", "mo", "ramp_out", "rmp");

    public HighwayExitRamp {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
    }

    @Override
    public void expand(ExpansionContext ctx) {
        String centerId = ctx.prefix(id, "n_center");
        ctx.addNode(centerId, "INTERSECTION", center.x, center.y);

        Map<String, String> entryNode = new LinkedHashMap<>();
        Map<String, String> exitNode = new LinkedHashMap<>();
        for (String arm : ALL_ARMS) {
            Point2D.Double p = armEndpoint(arm);
            String nick = ARM_NODE_NICK.get(arm);
            String entryId = ctx.prefix(id, "n_" + nick);
            String exitId = ctx.prefix(id, "n_" + nick + "_exit");
            ctx.addNode(entryId, "ENTRY", p.x, p.y);
            ctx.addNode(exitId, "EXIT", p.x, p.y);
            entryNode.put(arm, entryId);
            exitNode.put(arm, exitId);
        }

        // Incoming highway: main_in ENTRY → centre.
        String rMainIn = ctx.prefix(id, "r_main_in");
        ctx.addRoad(rMainIn, "Main Inbound", entryNode.get("main_in"), centerId,
                APPROACH_LEN, APPROACH_SPEED, ARM_LANE_COUNT.get("main_in"));
        ctx.addSpawn(rMainIn, 0, 0.0);

        // Downstream continuation: centre → main_out EXIT.
        String rMainOut = ctx.prefix(id, "r_main_out");
        ctx.addRoad(rMainOut, "Main Outbound", centerId, exitNode.get("main_out"),
                APPROACH_LEN, APPROACH_SPEED, ARM_LANE_COUNT.get("main_out"));
        ctx.addDespawn(rMainOut, 0, APPROACH_LEN);

        // Exit ramp: centre → ramp_out EXIT.
        String rRampOut = ctx.prefix(id, "r_ramp_out");
        ctx.addRoad(rRampOut, "Exit Ramp", centerId, exitNode.get("ramp_out"),
                APPROACH_LEN, APPROACH_SPEED, ARM_LANE_COUNT.get("ramp_out"));
        ctx.addDespawn(rRampOut, 0, APPROACH_LEN);

        // Arm registration (logical names).
        for (String arm : ALL_ARMS) {
            ctx.registerArm(id, arm, entryNode.get(arm), exitNode.get(arm), armEndpoint(arm));
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
}
