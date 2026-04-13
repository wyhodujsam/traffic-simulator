package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary.ExpansionException;
import com.trafficsimulator.vision.components.ArmRef;
import com.trafficsimulator.vision.components.Connection;
import com.trafficsimulator.vision.components.RoundaboutFourArm;
import com.trafficsimulator.vision.components.SignalFourWay;
import com.trafficsimulator.vision.components.StraightSegment;
import com.trafficsimulator.vision.components.TIntersection;

class MapComponentLibraryTest {

    private static final List<String> ALL_ARMS = List.of("north", "east", "south", "west");

    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    // RoundaboutFourArm geometry: ring radius 28, approach 200 → arm endpoint at center ± 228.
    private static final double RB_OFFSET = RoundaboutFourArm.RING_R + RoundaboutFourArm.APPROACH_LEN;

    @Test
    void stitch_mergesCoincidentArms() {
        // rb1 east arm endpoint = (628, 300); rb2 placed so west arm endpoint = (628, 300).
        var rb1 = new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS);
        var rb2 = new RoundaboutFourArm(
                "rb2", new Point2D.Double(400 + 2 * RB_OFFSET, 300), 0, ALL_ARMS);
        var conn = new Connection(new ArmRef("rb1", "east"), new ArmRef("rb2", "west"));

        MapConfig cfg = library.expand(List.of(rb1, rb2), List.of(conn));

        // Exactly one merged INTERSECTION node.
        long mergedNodes =
                cfg.getNodes().stream().filter(n -> n.getId().startsWith("merged__")).count();
        assertThat(mergedNodes).isEqualTo(1);

        // The 4 deprecated ENTRY/EXIT arm nodes are gone.
        Set<String> nodeIds =
                cfg.getNodes().stream()
                        .map(MapConfig.NodeConfig::getId)
                        .collect(Collectors.toSet());
        assertThat(nodeIds).doesNotContain(
                "rb1__n_east", "rb1__n_east_exit", "rb2__n_west", "rb2__n_west_exit");

        // Spawns and despawns dropped for fused arms (rb1.east + rb2.west) → 8 → 6.
        assertThat(cfg.getSpawnPoints()).hasSize(6);
        assertThat(cfg.getDespawnPoints()).hasSize(6);

        // A new PRIORITY intersection sits at the merged node id.
        assertThat(cfg.getIntersections())
                .anySatisfy(
                        ic -> {
                            assertThat(ic.getNodeId()).startsWith("merged__rb1_east__rb2_west");
                            assertThat(ic.getType()).isEqualTo("PRIORITY");
                        });
    }

    @Test
    void stitch_rejectsNonCoincidentArms() {
        var rb1 = new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS);
        var rb2 = new RoundaboutFourArm("rb2", new Point2D.Double(1200, 300), 0, ALL_ARMS);
        var conn = new Connection(new ArmRef("rb1", "east"), new ArmRef("rb2", "west"));

        assertThatThrownBy(() -> library.expand(List.of(rb1, rb2), List.of(conn)))
                .isInstanceOf(ExpansionException.class)
                .hasMessageContaining("Insert a STRAIGHT_SEGMENT");
    }

    @Test
    void stitch_bridgesWithStraightSegment() {
        // rb1.east = (628, 300), rb2 placed so rb2.west = (828, 300). Bridge: seg from
        // (628, 300) to (828, 300).
        var rb1 = new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS);
        var rb2 = new RoundaboutFourArm("rb2", new Point2D.Double(1056, 300), 0, ALL_ARMS);
        var seg =
                new StraightSegment(
                        "seg1", new Point2D.Double(628, 300), new Point2D.Double(828, 300), 200);

        var c1 = new Connection(new ArmRef("rb1", "east"), new ArmRef("seg1", "start"));
        var c2 = new Connection(new ArmRef("seg1", "end"), new ArmRef("rb2", "west"));

        MapConfig cfg = library.expand(List.of(rb1, rb2, seg), List.of(c1, c2));

        // Two merged nodes, one per stitching pair.
        assertThat(cfg.getNodes().stream().filter(n -> n.getId().startsWith("merged__")))
                .hasSize(2);

        // The bridge road must still exist and reference the two merged nodes.
        var bridgeRoad =
                cfg.getRoads().stream()
                        .filter(r -> r.getId().equals("seg1__r_main"))
                        .findFirst()
                        .orElseThrow();
        assertThat(bridgeRoad.getFromNodeId()).startsWith("merged__");
        assertThat(bridgeRoad.getToNodeId()).startsWith("merged__");
        assertThat(bridgeRoad.getFromNodeId()).isNotEqualTo(bridgeRoad.getToNodeId());

        // Validator passed (would have thrown otherwise).
        assertThat(cfg.getNodes()).isNotEmpty();
    }

    @Test
    void stitch_orphanArmsRemainBoundaries() {
        var rb1 = new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS);

        MapConfig cfg = library.expand(List.of(rb1), List.of());

        long entries =
                cfg.getNodes().stream().filter(n -> "ENTRY".equals(n.getType())).count();
        long exits = cfg.getNodes().stream().filter(n -> "EXIT".equals(n.getType())).count();
        assertThat(entries).isEqualTo(4);
        assertThat(exits).isEqualTo(4);
        assertThat(cfg.getSpawnPoints()).hasSize(4);
        assertThat(cfg.getDespawnPoints()).hasSize(4);
    }

    @Test
    void stitch_twoTIntersectionsMergeAtSharedEndpoint() {
        // T1 at (400,300) with east arm endpoint (600,300); T2 at (800,300) with west endpoint
        // (600,300). Connect t1.east ↔ t2.west.
        var t1 =
                new TIntersection(
                        "t1",
                        new Point2D.Double(400, 300),
                        0,
                        List.of("north", "east", "south"));
        var t2 =
                new TIntersection(
                        "t2",
                        new Point2D.Double(800, 300),
                        0,
                        List.of("north", "west", "south"));
        var conn = new Connection(new ArmRef("t1", "east"), new ArmRef("t2", "west"));

        MapConfig cfg = library.expand(List.of(t1, t2), List.of(conn));

        // 3 PRIORITY intersections: 2 original T centres + 1 merged fuse point.
        long priorityIxns =
                cfg.getIntersections().stream()
                        .filter(ic -> "PRIORITY".equals(ic.getType()))
                        .count();
        assertThat(priorityIxns).isEqualTo(3);

        // Merged node lives at the midpoint (600, 300).
        var merged =
                cfg.getNodes().stream()
                        .filter(n -> n.getId().startsWith("merged__"))
                        .findFirst()
                        .orElseThrow();
        assertThat(merged.getX()).isEqualTo(600.0);
        assertThat(merged.getY()).isEqualTo(300.0);
    }

    @Test
    void stitch_rejectsComponentIdWithInSubstring() {
        // "train1" contains "in" — must be rejected at expand time so the merged-id naming
        // scheme (which substring-matches against _in/_out for IntersectionGeometry) stays safe.
        assertThatThrownBy(
                        () ->
                                library.expand(
                                        List.of(
                                                new RoundaboutFourArm(
                                                        "train1",
                                                        new Point2D.Double(400, 300),
                                                        0,
                                                        ALL_ARMS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain 'in'");
    }

    @Test
    void stitch_rejectsComponentIdWithOutSubstring() {
        assertThatThrownBy(
                        () ->
                                library.expand(
                                        List.of(
                                                new RoundaboutFourArm(
                                                        "scout",
                                                        new Point2D.Double(400, 300),
                                                        0,
                                                        ALL_ARMS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain 'in' or 'out'");
    }

    @Test
    void stitch_unknownArmReferenceFailsLoudly() {
        var rb1 = new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS);
        var conn = new Connection(new ArmRef("rb1", "east"), new ArmRef("ghost", "west"));

        assertThatThrownBy(() -> library.expand(List.of(rb1), List.of(conn)))
                .isInstanceOf(ExpansionException.class)
                .hasMessageContaining("Unknown arm reference");
    }

    @Test
    void stitch_signalFourWayBridgedToRoundabout_passesValidator() {
        // SignalFourWay APPROACH_LEN=280; signal at (500,300) east endpoint = (780,300).
        // Roundabout east-of-signal: place rb so west arm endpoint coincides with (780,300).
        // rb.center.x = 780 + 228 = 1008.
        var sig = new SignalFourWay("sig1", new Point2D.Double(500, 300), 0, ALL_ARMS);
        var rb = new RoundaboutFourArm("rb1", new Point2D.Double(1008, 300), 0, ALL_ARMS);
        var conn = new Connection(new ArmRef("sig1", "east"), new ArmRef("rb1", "west"));

        MapConfig cfg = library.expand(List.of(sig, rb), List.of(conn));

        // Signal phases still reference their original _in road ids — those roads must survive
        // (their toNode just changed from the signal centre to the merged node? NO: sig's
        // r_east_in goes from sig.east ENTRY → sig centre. The ENTRY (sig.east) is fused away,
        // so the road's FROM is rewritten to mergedId. Its TO is still the signal centre.)
        var eastIn =
                cfg.getRoads().stream()
                        .filter(r -> r.getId().equals("sig1__r_east_in"))
                        .findFirst()
                        .orElseThrow();
        assertThat(eastIn.getFromNodeId()).startsWith("merged__");
        assertThat(eastIn.getToNodeId()).isEqualTo("sig1__n_center");
    }
}
