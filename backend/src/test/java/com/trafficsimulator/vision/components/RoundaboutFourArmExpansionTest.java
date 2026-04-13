package com.trafficsimulator.vision.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary;

class RoundaboutFourArmExpansionTest {

    private static final List<String> ALL = List.of("north", "east", "south", "west");
    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    @Test
    void expandFullRoundabout_passesValidator() {
        MapConfig cfg = library.expand(List.of(
                new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL)));

        assertThat(cfg.getNodes()).hasSize(12);   // 4 ring + 4 ENTRY + 4 EXIT
        assertThat(cfg.getRoads()).hasSize(12);   // 4 _in + 4 _out + 4 ring
        assertThat(cfg.getIntersections()).hasSize(4)
                .allMatch(ic -> "ROUNDABOUT".equals(ic.getType()));
        assertThat(cfg.getSpawnPoints()).hasSize(4);
        assertThat(cfg.getDespawnPoints()).hasSize(4);
    }

    @Test
    void expandFullRoundabout_idsTopologicallyEquivalentToReferenceJson() {
        MapConfig cfg = library.expand(List.of(
                new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL)));

        Pattern nodePattern = Pattern.compile(
                "^rb1__(n_ring_[news]|n_(north|south|east|west)(_exit)?)$");
        assertThat(cfg.getNodes()).allSatisfy(n ->
                assertThat(nodePattern.matcher(n.getId()).matches())
                        .as("node id %s matches", n.getId()).isTrue());

        Set<String> roadIds = cfg.getRoads().stream()
                .map(MapConfig.RoadConfig::getId).collect(Collectors.toSet());
        // Every _in road must have a sibling _out road (so reverseRoadId works).
        for (String rid : roadIds) {
            if (rid.endsWith("_in")) {
                assertThat(roadIds).contains(rid.substring(0, rid.length() - 3) + "_out");
            }
        }
        // All ring road ids start with rb1__r_ring_
        assertThat(roadIds.stream().filter(s -> s.contains("_ring_")).count()).isEqualTo(4);
    }

    @Test
    void expandPartialArms_stillEmitsAllRingNodesButOnlyPresentArmRoads() {
        MapConfig cfg = library.expand(List.of(new RoundaboutFourArm(
                "rb1", new Point2D.Double(400, 300), 0, List.of("north", "south"))));

        assertThat(cfg.getNodes().stream().filter(n -> n.getId().contains("n_ring_"))).hasSize(4);
        long inOut = cfg.getRoads().stream()
                .filter(r -> r.getId().endsWith("_in") || r.getId().endsWith("_out")).count();
        assertThat(inOut).isEqualTo(4); // 2 in + 2 out
        assertThat(cfg.getRoads()).hasSize(8); // 2 in + 2 out + 4 ring
    }

    @Test
    void rejectsBadComponentId() {
        assertThatThrownBy(() -> library.expand(List.of(
                new RoundaboutFourArm("ringinside", new Point2D.Double(400, 300), 0, ALL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain 'in'");
    }

    @Test
    void ringNodeGeometry_matchesReferenceRoundaboutJson() {
        // Reference roundabout.json: ring_n=(400,272), ring_e=(428,300), ring_s=(400,328), ring_w=(372,300)
        MapConfig cfg = library.expand(List.of(
                new RoundaboutFourArm("rb1", new Point2D.Double(400, 300), 0, ALL)));
        var ringNodes = cfg.getNodes().stream()
                .filter(n -> n.getId().contains("n_ring_"))
                .collect(Collectors.toMap(MapConfig.NodeConfig::getId, n -> n));
        assertThat(ringNodes.get("rb1__n_ring_n").getY()).isCloseTo(272.0, within(0.001));
        assertThat(ringNodes.get("rb1__n_ring_e").getX()).isCloseTo(428.0, within(0.001));
        assertThat(ringNodes.get("rb1__n_ring_s").getY()).isCloseTo(328.0, within(0.001));
        assertThat(ringNodes.get("rb1__n_ring_w").getX()).isCloseTo(372.0, within(0.001));
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
