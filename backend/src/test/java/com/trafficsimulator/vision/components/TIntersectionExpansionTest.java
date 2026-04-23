package com.trafficsimulator.vision.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary;

class TIntersectionExpansionTest {

    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    @Test
    void threeArmT_passesValidator() {
        MapConfig cfg = library.expand(List.of(
                new TIntersection("t1", new Point2D.Double(400, 300), 0,
                        List.of("north", "east", "west"))));

        assertThat(cfg.getNodes()).hasSize(7);   // 1 centre + 3 ENTRY + 3 EXIT
        assertThat(cfg.getRoads()).hasSize(6);   // 3 in + 3 out
        assertThat(cfg.getIntersections()).hasSize(1);
        assertThat(cfg.getIntersections().get(0).getType()).isEqualTo("PRIORITY");
    }

    @Test
    void wrongArmCount_throws() {
        assertThatThrownBy(() -> new TIntersection(
                "t1", new Point2D.Double(400, 300), 0,
                List.of("north", "east", "south", "west")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 3 arms");
    }

    @Test
    void inOutPairsConsistent() {
        MapConfig cfg = library.expand(List.of(
                new TIntersection("t1", new Point2D.Double(400, 300), 0,
                        List.of("north", "east", "west"))));
        var ids = cfg.getRoads().stream().map(MapConfig.RoadConfig::getId).toList();
        for (String rid : ids) {
            if (rid.endsWith("_in")) {
                assertThat(ids).contains(rid.substring(0, rid.length() - 3) + "_out");
            }
        }
    }
}
