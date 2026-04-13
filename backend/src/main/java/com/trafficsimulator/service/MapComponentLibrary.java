package com.trafficsimulator.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapConfig.IntersectionConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.vision.components.ComponentSpec;
import com.trafficsimulator.vision.components.Connection;
import com.trafficsimulator.vision.components.ExpansionContext;
import com.trafficsimulator.vision.components.ExpansionContext.ArmRecord;

import lombok.RequiredArgsConstructor;

/**
 * Phase 21 component-library entry point. Expands a list of {@link ComponentSpec} into a single
 * {@link MapConfig} that passes {@link MapValidator}.
 *
 * <p>Plan 21-02 implements stitching: each {@link Connection} either fuses two coincident arm
 * endpoints (≤{@link #MERGE_TOLERANCE_PX}px apart) into a shared INTERSECTION node, or fails
 * loudly with an {@link ExpansionException} instructing the caller to insert a STRAIGHT_SEGMENT.
 * Orphan arms (not referenced in any Connection) survive as ENTRY/EXIT network boundaries.
 */
@Service
@RequiredArgsConstructor
public class MapComponentLibrary {

    /** Allowed component-id pattern: lowercase letter then lowercase alphanumerics. */
    public static final Pattern ID_RX = Pattern.compile("^[a-z][a-z0-9]*$");

    /** Maximum world-pixel distance between two arm endpoints to consider them coincident. */
    public static final double MERGE_TOLERANCE_PX = 5.0;

    private final MapValidator mapValidator;

    /** Expand without connections (single component or fully-disjoint set). */
    public MapConfig expand(List<ComponentSpec> components) {
        return expand(components, List.of());
    }

    /**
     * Expand a list of components and stitch them together via {@code connections}.
     *
     * @throws IllegalArgumentException when any component id violates the naming rule.
     * @throws ExpansionException       when stitching cannot complete (non-coincident arms,
     *                                  unknown arm reference, validator failure).
     */
    public MapConfig expand(List<ComponentSpec> components, List<Connection> connections) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("MapComponentLibrary.expand requires at least one component");
        }
        for (ComponentSpec c : components) {
            validateId(c.id());
        }
        ExpansionContext ctx = new ExpansionContext();
        for (ComponentSpec c : components) {
            c.expand(ctx);
        }

        if (connections != null) {
            for (Connection conn : connections) {
                stitchOne(ctx, conn);
            }
        }

        // Defensive: drop intersection configs whose nodeId no longer appears in any road.
        Set<String> referencedNodes =
                ctx.roads.stream()
                        .flatMap(r -> Stream.of(r.getFromNodeId(), r.getToNodeId()))
                        .collect(Collectors.toSet());
        ctx.intersections.removeIf(ic -> !referencedNodes.contains(ic.getNodeId()));

        MapConfig cfg = ctx.toMapConfig("vision-generated", "AI Generated Map (component library)");
        List<String> errors = mapValidator.validate(cfg);
        if (!errors.isEmpty()) {
            throw new ExpansionException(
                    "Stitched MapConfig failed validation: " + String.join(", ", errors));
        }
        return cfg;
    }

    private void stitchOne(ExpansionContext ctx, Connection conn) {
        ArmRecord ra;
        ArmRecord rb;
        try {
            ra = ctx.lookupArm(conn.a());
            rb = ctx.lookupArm(conn.b());
        } catch (IllegalArgumentException ex) {
            throw new ExpansionException(ex.getMessage(), ex);
        }
        double d = ra.worldPos().distance(rb.worldPos());
        if (d > MERGE_TOLERANCE_PX) {
            throw new ExpansionException(
                    String.format(
                            "Arms %s.%s and %s.%s are %.1fpx apart (>%.1f). Insert a STRAIGHT_SEGMENT between them.",
                            conn.a().componentId(),
                            conn.a().armName(),
                            conn.b().componentId(),
                            conn.b().armName(),
                            d,
                            MERGE_TOLERANCE_PX));
        }
        // mergedId uses double-underscores between fields and ALWAYS contains a digit-or-letter
        // run that does not include the substring "_in" or "_out", because component ids are
        // validated to exclude "in"/"out" and arm names are from the closed set
        // {north, east, south, west, start, end}. None of those contain "in"/"out". This makes
        // IntersectionGeometry.reverseRoadId (which does naive _in→_out string replace) safe.
        String mergedId =
                String.format(
                        "merged__%s_%s__%s_%s",
                        conn.a().componentId(),
                        conn.a().armName(),
                        conn.b().componentId(),
                        conn.b().armName());
        double mx = (ra.worldPos().x + rb.worldPos().x) / 2.0;
        double my = (ra.worldPos().y + rb.worldPos().y) / 2.0;
        ctx.addNode(mergedId, "INTERSECTION", mx, my);

        Set<String> dead = new HashSet<>();
        dead.add(ra.entryNodeId());
        dead.add(ra.exitNodeId());
        dead.add(rb.entryNodeId());
        dead.add(rb.exitNodeId());

        // Order matters: drop spawn/despawn FIRST (uses pre-rewrite road endpoints), then
        // rewrite road endpoints, then drop the dead nodes.
        ctx.dropSpawnDespawnForRoadsReferencing(dead);
        ctx.rewriteRoadEndpoint(dead, mergedId);
        ctx.dropNodes(dead);

        IntersectionConfig ic = new IntersectionConfig();
        ic.setNodeId(mergedId);
        ic.setType("PRIORITY");
        ic.setIntersectionSize(24);
        ctx.intersections.add(ic);
    }

    private void validateId(String id) {
        if (id == null
                || !ID_RX.matcher(id).matches()
                || id.contains("in")
                || id.contains("out")) {
            throw new IllegalArgumentException(
                    "Component id '"
                            + id
                            + "' must match ^[a-z][a-z0-9]*$ and must not contain 'in' or 'out' substrings");
        }
    }

    /**
     * Thrown by {@link #expand(List, List)} when stitching cannot complete: unknown arm
     * reference, non-coincident arms without a bridging segment, or downstream validator failure.
     */
    public static class ExpansionException extends RuntimeException {
        public ExpansionException(String message) {
            super(message);
        }

        public ExpansionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
