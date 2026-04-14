package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Viaduct / overpass — two independent through-road pairs that cross at different heights.
 *
 * <p>Lower road connects {@code south} ↔ {@code north}; upper road connects {@code west} ↔
 * {@code east}. There is <strong>no shared node</strong> at the crossing point — the physics
 * engine already handles "two roads passing near each other with no intersection" correctly.
 * No {@link com.trafficsimulator.config.MapConfig.IntersectionConfig} is emitted.
 *
 * <p>Each through-road is represented as two one-way roads (an {@code _in} direction and an
 * {@code _out} direction) spanning the full crossing length {@code 2 * APPROACH_LEN}. The
 * {@code _in}/{@code _out} naming ensures {@code IntersectionGeometry.reverseRoadId} returns a
 * real sibling road id for every emitted approach.
 *
 * <p>Arm endpoints follow the standard compass convention (north=270°, east=0°, south=90°,
 * west=180° before {@code rotationDeg}).
 */
public record Viaduct(String id, Point2D.Double center, double rotationDeg)
        implements ComponentSpec {

    public static final double APPROACH_LEN = 200.0;
    public static final double APPROACH_SPEED = 13.9;

    private static final List<String> ALL_ARMS = List.of("north", "east", "south", "west");
    private static final Map<String, Double> ARM_ANGLE_DEG =
            Map.of("north", 270.0, "east", 0.0, "south", 90.0, "west", 180.0);

    public Viaduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
    }

    @Override
    public void expand(ExpansionContext ctx) {
        // Emit ENTRY + EXIT nodes per arm at the arm endpoint.
        Map<String, String> entryNode = new LinkedHashMap<>();
        Map<String, String> exitNode = new LinkedHashMap<>();
        for (String arm : ALL_ARMS) {
            Point2D.Double p = armEndpoint(arm);
            String entryId = ctx.prefix(id, "n_" + arm);
            String exitId = ctx.prefix(id, "n_" + arm + "_exit");
            ctx.addNode(entryId, "ENTRY", p.x, p.y);
            ctx.addNode(exitId, "EXIT", p.x, p.y);
            entryNode.put(arm, entryId);
            exitNode.put(arm, exitId);
        }

        double fullLen = 2 * APPROACH_LEN;

        // Lower road: south ↔ north pair (two one-way roads across the full span).
        String southIn = ctx.prefix(id, "r_south_in");
        String southOut = ctx.prefix(id, "r_south_out");
        ctx.addRoad(southIn, "Lower South→North", entryNode.get("south"), exitNode.get("north"),
                fullLen, APPROACH_SPEED, 1);
        ctx.addRoad(southOut, "Lower North→South", entryNode.get("north"), exitNode.get("south"),
                fullLen, APPROACH_SPEED, 1);
        ctx.addSpawn(southIn, 0, 0.0);
        ctx.addDespawn(southIn, 0, fullLen);
        ctx.addSpawn(southOut, 0, 0.0);
        ctx.addDespawn(southOut, 0, fullLen);

        // Upper road: west ↔ east pair.
        String westIn = ctx.prefix(id, "r_west_in");
        String westOut = ctx.prefix(id, "r_west_out");
        ctx.addRoad(westIn, "Upper West→East", entryNode.get("west"), exitNode.get("east"),
                fullLen, APPROACH_SPEED, 1);
        ctx.addRoad(westOut, "Upper East→West", entryNode.get("east"), exitNode.get("west"),
                fullLen, APPROACH_SPEED, 1);
        ctx.addSpawn(westIn, 0, 0.0);
        ctx.addDespawn(westIn, 0, fullLen);
        ctx.addSpawn(westOut, 0, 0.0);
        ctx.addDespawn(westOut, 0, fullLen);

        // Register all 4 arms so the stitcher can find them.
        for (String arm : ALL_ARMS) {
            ctx.registerArm(id, arm, entryNode.get(arm), exitNode.get(arm), armEndpoint(arm));
        }
        // No IntersectionConfig — the crossing has no junction control.
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
