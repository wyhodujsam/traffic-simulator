package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Connector between two arms. Standalone, it emits a single ENTRY → EXIT road. The emitted road
 * length is {@code max(lengthPx, geometricDistance)} (per RESEARCH pitfall 4: never under-shoot
 * the actual rendered distance).
 */
public record StraightSegment(
        String id, Point2D.Double startPx, Point2D.Double endPx, double lengthPx)
        implements ComponentSpec {

    public static final double SPEED = 11.1;

    public StraightSegment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startPx, "startPx");
        Objects.requireNonNull(endPx, "endPx");
        if (lengthPx <= 0) {
            throw new IllegalArgumentException("StraightSegment lengthPx must be positive");
        }
    }

    @Override
    public double rotationDeg() {
        return 0.0;
    }

    @Override
    public void expand(ExpansionContext ctx) {
        String startId = ctx.prefix(id, "n_start");
        String endId = ctx.prefix(id, "n_end_exit");
        ctx.addNode(startId, "ENTRY", startPx.x, startPx.y);
        ctx.addNode(endId, "EXIT", endPx.x, endPx.y);

        double geo = Math.hypot(endPx.x - startPx.x, endPx.y - startPx.y);
        double effectiveLen = Math.max(lengthPx, geo);
        // NOTE: must NOT end in "_in"/"_out" — segment is a single one-way connector with no
        // sibling, so IntersectionGeometry.reverseRoadId (naive _in→_out replace) would
        // otherwise hand back a road id that doesn't exist. Plain "r_main" keeps the contract.
        String roadId = ctx.prefix(id, "r_main");
        ctx.addRoad(roadId, "Segment " + id, startId, endId, effectiveLen, SPEED, 1);
        ctx.addSpawn(roadId, 0, 0.0);
        ctx.addDespawn(roadId, 0, effectiveLen);
        // For stitching: each terminal acts as both entry and exit for the single road that
        // touches it. The stitcher only needs to identify the node id at that endpoint to
        // rewrite road references; it doesn't matter whether we call it entry or exit since
        // the merge replaces both with the new shared INTERSECTION id.
        ctx.registerArm(id, "start", startId, startId, startPx);
        ctx.registerArm(id, "end", endId, endId, endPx);
    }

    @Override
    public Map<String, Point2D.Double> armEndpoints() {
        Map<String, Point2D.Double> out = new LinkedHashMap<>();
        out.put("start", startPx);
        out.put("end", endPx);
        return out;
    }
}
