package com.trafficsimulator.vision.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary;

class StraightSegmentExpansionTest {

    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    @Test
    void standaloneSegment_passesValidator() {
        MapConfig cfg = library.expand(List.of(new StraightSegment(
                "seg1", new Point2D.Double(0, 0), new Point2D.Double(100, 0), 100)));

        assertThat(cfg.getNodes()).hasSize(2);
        assertThat(cfg.getRoads()).hasSize(1);
        assertThat(cfg.getRoads().get(0).getLength()).isEqualTo(100.0);
        assertThat(cfg.getSpawnPoints()).hasSize(1);
        assertThat(cfg.getDespawnPoints()).hasSize(1);
    }

    @Test
    void roadLength_isMaxOfDeclaredAndGeometric() {
        // declared 50 px but geometric distance is 200 → expect 200
        MapConfig cfg = library.expand(List.of(new StraightSegment(
                "seg1", new Point2D.Double(0, 0), new Point2D.Double(200, 0), 50)));
        assertThat(cfg.getRoads().get(0).getLength()).isEqualTo(200.0);
    }

    @Test
    void rejectsBadComponentId() {
        // "input" contains "in" and "out" — should be rejected by the library.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                library.expand(List.of(new StraightSegment(
                        "input", new Point2D.Double(0, 0), new Point2D.Double(100, 0), 100))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void armEndpoints_returnStartAndEnd() {
        StraightSegment seg = new StraightSegment(
                "seg1", new Point2D.Double(10, 20), new Point2D.Double(110, 20), 100);
        var arms = seg.armEndpoints();
        assertThat(arms).containsKeys("start", "end");
        assertThat(arms.get("start")).isEqualTo(new Point2D.Double(10, 20));
        assertThat(arms.get("end")).isEqualTo(new Point2D.Double(110, 20));
    }

    @Test
    void singleSegmentMapHasOneRoad_noIntersections() {
        MapConfig cfg = library.expand(List.of(new StraightSegment(
                "seg1", new Point2D.Double(0, 0), new Point2D.Double(100, 0), 100)));
        assertThat(cfg.getIntersections()).isEmpty();
        // Validate that the road id is the prefixed _in form (so MapValidator wouldn't choke later)
        assertThat(cfg.getRoads().get(0).getId()).isEqualTo("seg1__r_main_in");
    }
}
