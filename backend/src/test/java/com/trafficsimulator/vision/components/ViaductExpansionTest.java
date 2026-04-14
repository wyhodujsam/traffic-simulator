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

class ViaductExpansionTest {

    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    @Test
    void fourArms_passesValidator() {
        MapConfig cfg = library.expand(List.of(
                new Viaduct("v1", new Point2D.Double(400, 300), 0)));

        // 4 ENTRY + 4 EXIT, no centre node
        assertThat(cfg.getNodes()).hasSize(8);
        // 2 one-way lower pair + 2 one-way upper pair = 4 roads
        assertThat(cfg.getRoads()).hasSize(4);
        assertThat(cfg.getIntersections()).isEmpty();
    }

    @Test
    void inOutPairsConsistent() {
        MapConfig cfg = library.expand(List.of(
                new Viaduct("v1", new Point2D.Double(400, 300), 0)));
        Set<String> ids = cfg.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());
        for (String rid : ids) {
            if (rid.endsWith("_in")) {
                assertThat(ids).contains(rid.substring(0, rid.length() - 3) + "_out");
            }
        }
    }

    @Test
    void crossingHasNoSharedNode() {
        Point2D.Double centre = new Point2D.Double(400, 300);
        MapConfig cfg = library.expand(List.of(new Viaduct("v1", centre, 0)));
        // No node may coincide with the centre — crossing has no shared intersection node.
        for (MapConfig.NodeConfig n : cfg.getNodes()) {
            boolean atCentre = Math.abs(n.getX() - centre.x) < 0.001
                    && Math.abs(n.getY() - centre.y) < 0.001;
            assertThat(atCentre)
                    .as("node %s should not sit at viaduct centre", n.getId())
                    .isFalse();
        }
    }

    @Test
    void rotation90_swapsAxes() {
        Viaduct v = new Viaduct("v1", new Point2D.Double(400, 300), 90);
        // With rotation=90 (CW in screen space), south arm (base 90°) ends up at 180° → west of centre.
        Point2D.Double south = v.armEndpoints().get("south");
        assertThat(south.x).isLessThan(400);
        assertThat(south.y).isCloseTo(300, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void armEndpoints_returnsFourNamedArms() {
        Viaduct v = new Viaduct("v1", new Point2D.Double(400, 300), 0);
        assertThat(v.armEndpoints().keySet())
                .containsExactlyInAnyOrder("north", "east", "south", "west");
    }
}
