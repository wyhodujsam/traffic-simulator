package com.trafficsimulator.vision.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary;

/**
 * Verifies that {@code IntersectionGeometry.reverseRoadId} (which performs a naive
 * {@code _in → _out} string replacement) returns a real road id for every {@code _in} road
 * emitted by the component library — even after stitching multiple components together.
 *
 * <p>{@code IntersectionGeometry} is package-private to {@code com.trafficsimulator.engine}, so
 * we re-implement the same string-replace contract here and assert the result is present in the
 * road id set. If that engine method ever changes, this test must change too.
 */
class ReverseRoadIdComponentTest {

    private static final List<String> ALL_ARMS = List.of("north", "east", "south", "west");
    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    /** Mirrors {@code IntersectionGeometry.reverseRoadId} (engine package-private). */
    private static String reverseRoadId(String inboundRoadId) {
        return inboundRoadId.replace("_in", "_out");
    }

    @Test
    void reverseRoadId_flipsForEveryEmittedRoad_singleRoundabout() {
        MapConfig cfg = library.expand(List.of(
                new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS)));

        Set<String> roadIds =
                cfg.getRoads().stream()
                        .map(MapConfig.RoadConfig::getId)
                        .collect(Collectors.toSet());
        for (String rid : roadIds) {
            if (rid.endsWith("_in")) {
                assertThat(roadIds)
                        .as("reverseRoadId(%s)", rid)
                        .contains(reverseRoadId(rid));
            }
        }
    }

    @Test
    void reverseRoadId_flipsForEveryEmittedRoad_twoRoundaboutsBridged() {
        var rb1 = new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL_ARMS);
        var rb2 = new RoundaboutFourArm("rb2", new Point2D.Double(1056, 300), 0, ALL_ARMS);
        var seg =
                new StraightSegment(
                        "seg1",
                        new Point2D.Double(628, 300),
                        new Point2D.Double(828, 300),
                        200);
        var c1 = new Connection(new ArmRef("rb1", "east"), new ArmRef("seg1", "start"));
        var c2 = new Connection(new ArmRef("seg1", "end"), new ArmRef("rb2", "west"));

        MapConfig cfg = library.expand(List.of(rb1, rb2, seg), List.of(c1, c2));
        Set<String> roadIds =
                cfg.getRoads().stream()
                        .map(MapConfig.RoadConfig::getId)
                        .collect(Collectors.toSet());

        // Sanity: bridge segment's road is present (renamed in plan 21-02 to avoid the _in
        // suffix because the segment has no sibling _out road).
        assertThat(roadIds).contains("seg1__r_main");

        for (String rid : roadIds) {
            if (rid.endsWith("_in")) {
                assertThat(roadIds)
                        .as("reverseRoadId(%s) must exist as a real road", rid)
                        .contains(reverseRoadId(rid));
            }
        }

        // Critical: no node id contains _in or _out, so the reverse op cannot accidentally
        // collide with a node-named road through the substring replace.
        Set<String> nodeIds =
                cfg.getNodes().stream()
                        .map(MapConfig.NodeConfig::getId)
                        .collect(Collectors.toSet());
        for (String nid : nodeIds) {
            assertThat(nid)
                    .as("node id %s must not contain _in/_out (would corrupt reverseRoadId)", nid)
                    .doesNotContain("_in")
                    .doesNotContain("_out");
        }
    }

    @Test
    void reverseRoadId_flipsForEveryEmittedRoad_viaductStandalone() {
        MapConfig cfg = library.expand(List.of(
                new Viaduct("v1", new Point2D.Double(400, 300), 0)));

        Set<String> roadIds = cfg.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());
        for (String rid : roadIds) {
            if (rid.endsWith("_in")) {
                assertThat(roadIds)
                        .as("reverseRoadId(%s)", rid)
                        .contains(reverseRoadId(rid));
            }
        }
        Set<String> nodeIds = cfg.getNodes().stream()
                .map(MapConfig.NodeConfig::getId)
                .collect(Collectors.toSet());
        for (String nid : nodeIds) {
            assertThat(nid)
                    .as("viaduct node id %s must not contain _in/_out", nid)
                    .doesNotContain("_in")
                    .doesNotContain("_out");
        }
    }

    @Test
    void reverseRoadId_flipsForEveryEmittedRoad_exitRampStandalone() {
        MapConfig cfg = library.expand(List.of(
                new HighwayExitRamp("hx1", new Point2D.Double(400, 300), 0)));

        Set<String> roadIds = cfg.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());
        for (String rid : roadIds) {
            if (rid.endsWith("_in")) {
                assertThat(roadIds)
                        .as("reverseRoadId(%s)", rid)
                        .contains(reverseRoadId(rid));
            }
        }
        Set<String> nodeIds = cfg.getNodes().stream()
                .map(MapConfig.NodeConfig::getId)
                .collect(Collectors.toSet());
        for (String nid : nodeIds) {
            assertThat(nid)
                    .as("exit-ramp node id %s must not contain _in/_out", nid)
                    .doesNotContain("_in")
                    .doesNotContain("_out");
        }
    }

    @Test
    void reverseRoadId_flipsForEveryEmittedRoad_viaductBridgedToRoundabout() {
        // Viaduct at (400, 300); east arm endpoint = (400 + 200, 300) = (600, 300).
        // Roundabout at (1056, 300); west arm endpoint = (1056 - (RING_R + APPROACH_LEN), 300)
        //                                             = (1056 - 228, 300) = (828, 300).
        Viaduct v = new Viaduct("v1", new Point2D.Double(400, 300), 0);
        RoundaboutFourArm rb = new RoundaboutFourArm(
                "rb1", new Point2D.Double(1056, 300), 0, ALL_ARMS);
        StraightSegment seg = new StraightSegment(
                "seg1",
                new Point2D.Double(600, 300),
                new Point2D.Double(828, 300),
                228);
        Connection c1 = new Connection(new ArmRef("v1", "east"), new ArmRef("seg1", "start"));
        Connection c2 = new Connection(new ArmRef("seg1", "end"), new ArmRef("rb1", "west"));

        MapConfig cfg = library.expand(List.of(v, rb, seg), List.of(c1, c2));

        Set<String> roadIds = cfg.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());
        for (String rid : roadIds) {
            if (rid.endsWith("_in")) {
                assertThat(roadIds)
                        .as("reverseRoadId(%s) must exist after viaduct↔roundabout stitch", rid)
                        .contains(reverseRoadId(rid));
            }
        }
        Set<String> nodeIds = cfg.getNodes().stream()
                .map(MapConfig.NodeConfig::getId)
                .collect(Collectors.toSet());
        for (String nid : nodeIds) {
            assertThat(nid)
                    .as("stitched node id %s must not contain _in/_out", nid)
                    .doesNotContain("_in")
                    .doesNotContain("_out");
        }
    }
}
