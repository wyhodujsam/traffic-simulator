package com.trafficsimulator.vision.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary;

class HighwayExitRampExpansionTest {

    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    @Test
    void threeArms_passesValidator() {
        MapConfig cfg = library.expand(List.of(
                new HighwayExitRamp("hx1", new Point2D.Double(400, 300), 0)));

        // 1 centre + 3 ENTRY + 3 EXIT
        assertThat(cfg.getNodes()).hasSize(7);
        assertThat(cfg.getRoads()).hasSize(3);
        assertThat(cfg.getIntersections()).hasSize(1);
        assertThat(cfg.getIntersections().get(0).getType()).isEqualTo("PRIORITY");
    }

    @Test
    void inOutPairsConsistent() {
        MapConfig cfg = library.expand(List.of(
                new HighwayExitRamp("hx1", new Point2D.Double(400, 300), 0)));
        Set<String> ids = cfg.getRoads().stream()
                .map(MapConfig.RoadConfig::getId)
                .collect(Collectors.toSet());
        for (String rid : ids) {
            if (rid.endsWith("_in")) {
                assertThat(ids).contains(rid.substring(0, rid.length() - 3) + "_out");
            }
        }
        assertThat(ids).contains("hx1__r_main_in", "hx1__r_main_out", "hx1__r_ramp_out");
    }

    @Test
    void laneCountsMatchHighwayConvention() {
        MapConfig cfg = library.expand(List.of(
                new HighwayExitRamp("hx1", new Point2D.Double(400, 300), 0)));
        Map<String, Integer> lanes = cfg.getRoads().stream()
                .collect(Collectors.toMap(
                        MapConfig.RoadConfig::getId, MapConfig.RoadConfig::getLaneCount));
        assertThat(lanes.get("hx1__r_main_in")).isEqualTo(2);
        assertThat(lanes.get("hx1__r_main_out")).isEqualTo(2);
        assertThat(lanes.get("hx1__r_ramp_out")).isEqualTo(1);
    }

    @Test
    void armEndpoints_returnsThreeNamedArms() {
        HighwayExitRamp hx = new HighwayExitRamp("hx1", new Point2D.Double(400, 300), 0);
        assertThat(hx.armEndpoints().keySet())
                .containsExactlyInAnyOrder("main_in", "main_out", "ramp_out");
    }

    @Test
    void rampOut_diverges_fromMainAxis() {
        Point2D.Double centre = new Point2D.Double(400, 300);
        HighwayExitRamp hx = new HighwayExitRamp("hx1", centre, 0);
        Point2D.Double ramp = hx.armEndpoints().get("ramp_out");
        // Ramp must not be co-linear with the east/west main axis (y must differ from centre y).
        assertThat(Math.abs(ramp.y - centre.y)).isGreaterThan(1.0);
    }
}
