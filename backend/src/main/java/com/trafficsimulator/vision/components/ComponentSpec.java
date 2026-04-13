package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.Map;

/**
 * Sealed interface for the predefined map-component catalogue (Phase 21).
 *
 * <p>Each implementation is a deterministic, geometry-owning record. Claude only identifies
 * type + position + rotation; the record's {@link #expand(ExpansionContext)} produces nodes,
 * roads, intersections and spawn/despawn points that satisfy {@code MapValidator} and the
 * engine's {@code _in}/{@code _out} naming convention.
 */
public sealed interface ComponentSpec
        permits RoundaboutFourArm, SignalFourWay, TIntersection, StraightSegment {

    /** Component identifier (must match {@code ^[a-z][a-z0-9]*$}; no {@code in}/{@code out} substrings). */
    String id();

    /** Rotation about the component centre in degrees, clockwise in screen space. */
    double rotationDeg();

    /** Append this component's nodes/roads/intersections/spawn/despawn into {@code ctx}. */
    void expand(ExpansionContext ctx);

    /** Named arm endpoints in world pixels (e.g. "north" → (400, 100)). Used by the stitcher in plan 21-02. */
    Map<String, Point2D.Double> armEndpoints();
}
