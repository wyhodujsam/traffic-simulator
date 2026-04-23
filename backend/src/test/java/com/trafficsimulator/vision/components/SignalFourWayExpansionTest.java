package com.trafficsimulator.vision.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapConfig.IntersectionConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.MapComponentLibrary;

class SignalFourWayExpansionTest {

    private static final List<String> ALL = List.of("north", "east", "south", "west");
    private final MapComponentLibrary library = new MapComponentLibrary(new MapValidator());

    @Test
    void expandFullSignal_passesValidator_with9Nodes_8Roads_1Signal() {
        MapConfig cfg = library.expand(List.of(
                new SignalFourWay("sig1", new Point2D.Double(400, 300), 0, ALL)));

        assertThat(cfg.getNodes()).hasSize(9);   // 1 centre + 4 ENTRY + 4 EXIT
        assertThat(cfg.getRoads()).hasSize(8);   // 4 in + 4 out
        assertThat(cfg.getIntersections()).hasSize(1);

        IntersectionConfig ic = cfg.getIntersections().get(0);
        assertThat(ic.getType()).isEqualTo("SIGNAL");
        assertThat(ic.getNodeId()).isEqualTo("sig1__n_center");
        assertThat(ic.getSignalPhases()).hasSize(6);
    }

    @Test
    void signalPhases_referencePrefixedRoadIds() {
        MapConfig cfg = library.expand(List.of(
                new SignalFourWay("sig1", new Point2D.Double(400, 300), 0, ALL)));

        var phases = cfg.getIntersections().get(0).getSignalPhases();
        assertThat(phases.get(0).getGreenRoadIds())
                .containsExactlyInAnyOrder("sig1__r_north_in", "sig1__r_south_in");
        assertThat(phases.get(0).getType()).isEqualTo("GREEN");
        assertThat(phases.get(0).getDurationMs()).isEqualTo(25_000);
        assertThat(phases.get(2).getGreenRoadIds()).isEmpty();
        assertThat(phases.get(2).getType()).isEqualTo("ALL_RED");
        assertThat(phases.get(3).getGreenRoadIds())
                .containsExactlyInAnyOrder("sig1__r_west_in", "sig1__r_east_in");
    }

    @Test
    void rejectsPartialArms() {
        assertThatThrownBy(() -> new SignalFourWay(
                "sig1", new Point2D.Double(400, 300), 0, List.of("north", "south")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
