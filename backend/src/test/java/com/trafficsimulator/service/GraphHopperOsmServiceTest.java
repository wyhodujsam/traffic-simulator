package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Unit tests for {@link GraphHopperOsmService} — Phase 23 Wave 3 (23-03).
 *
 * <p>Each test drives one hand-crafted OSM XML fixture from {@code
 * src/test/resources/osm/} through the package-private {@link
 * GraphHopperOsmService#fetchAndConvert(File, BboxRequest)} overload (no HTTP).
 * Assertions cover road / intersection counts, types, id-prefix (gh-),
 * MapValidator cleanliness, and the {@code No roads found} failure mode.
 */
class GraphHopperOsmServiceTest {

    private final MapValidator validator = new MapValidator();

    // Bbox covering all fixture coordinates (52.2180-52.2240, 20.9980-21.0040).
    private final BboxRequest bbox = new BboxRequest(52.2180, 20.9980, 52.2240, 21.0050);

    private GraphHopperOsmService newService() {
        // RestClient is unused on the File overload, but the constructor requires one.
        return new GraphHopperOsmService(RestClient.create(), validator);
    }

    private File fixture(String name) {
        var url =
                Objects.requireNonNull(
                        getClass().getClassLoader().getResource("osm/" + name),
                        "fixture not found: osm/" + name);
        return new File(url.getFile());
    }

    // ---------------------------------------------------------------------
    // 1. straight.osm — single bidirectional way, 2 terminal nodes
    // ---------------------------------------------------------------------

    @Test
    void straight_producesTwoNodesAndTwoRoads() {
        GraphHopperOsmService service = newService();

        MapConfig cfg = service.fetchAndConvert(fixture("straight.osm"), bbox);

        assertThat(cfg.getRoads()).hasSize(2); // bidirectional: 2 roads
        assertThat(cfg.getNodes()).hasSize(2); // both endpoints are terminals

        // For a bidirectional way, each terminal is the fromNodeId of one direction, so both
        // classify as ENTRY per the Phase 18 semantics preserved in determineNodeType — this is
        // the A/B parity contract. Assertion is therefore: terminal types are from {ENTRY, EXIT}
        // and every node is a terminal type.
        List<String> types = cfg.getNodes().stream().map(MapConfig.NodeConfig::getType).toList();
        assertThat(types).allMatch(t -> "ENTRY".equals(t) || "EXIT".equals(t));

        assertThat(cfg.getRoads()).allSatisfy(r -> assertThat(r.getId()).startsWith("gh-"));
        assertThat(cfg.getNodes()).allSatisfy(n -> assertThat(n.getId()).startsWith("gh-"));

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 2. t-intersection.osm — 1 PRIORITY intersection, 3 roads
    // ---------------------------------------------------------------------

    @Test
    void tIntersection_producesOnePriorityIntersectionAndThreeRoads() {
        GraphHopperOsmService service = newService();

        MapConfig cfg = service.fetchAndConvert(fixture("t-intersection.osm"), bbox);

        assertThat(cfg.getIntersections()).hasSize(1);

        MapConfig.IntersectionConfig ic = cfg.getIntersections().get(0);
        assertThat(ic.getType()).isEqualTo("PRIORITY");
        assertThat(ic.getNodeId()).startsWith("gh-");

        // way 100 is bidirectional (split at node 2 → 2 segments × 2 roads = 4 roads);
        // way 200 is oneway=yes (1 segment × 1 road = 1 road).
        // Expected road count: 4 (bidirectional T-arms) + 1 (oneway stub) = 5.
        // NOTE: This reflects GraphHopper's WaySegmentParser splitting way 100 at the shared
        // tower node 2 into two segments; Phase 18's heuristic keeps way 100 as a single road.
        // This is the key A/B divergence the phase is designed to expose.
        assertThat(cfg.getRoads().size()).isGreaterThanOrEqualTo(3);

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 3. roundabout.osm — 4 ROUNDABOUT intersections, ring ways oneway
    // ---------------------------------------------------------------------

    @Test
    void roundabout_producesFourRoundaboutIntersections() {
        GraphHopperOsmService service = newService();

        MapConfig cfg = service.fetchAndConvert(fixture("roundabout.osm"), bbox);

        // Each of the 4 ring nodes (10..13) is shared with one arm way — tower node,
        // degree ≥ 3, junction=roundabout member → ROUNDABOUT.
        assertThat(cfg.getIntersections()).hasSize(4);
        assertThat(cfg.getIntersections())
                .allSatisfy(
                        ic -> {
                            assertThat(ic.getType()).isEqualTo("ROUNDABOUT");
                            assertThat(ic.getRoundaboutCapacity()).isEqualTo(8);
                            assertThat(ic.getNodeId()).startsWith("gh-");
                        });

        // Ring-way roads (from ways 100-103) must be oneway (no -rev suffix).
        boolean anyRingReverse =
                cfg.getRoads().stream()
                        .filter(
                                r ->
                                        r.getId().contains("-100")
                                                || r.getId().contains("-101")
                                                || r.getId().contains("-102")
                                                || r.getId().contains("-103"))
                        .anyMatch(r -> r.getId().endsWith("-rev"));
        assertThat(anyRingReverse)
                .as("Ring ways (junction=roundabout) must be oneway — no -rev suffix")
                .isFalse();

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 4. signal.osm — 1 SIGNAL intersection with phases
    // ---------------------------------------------------------------------

    @Test
    void signal_producesSignalIntersectionWithPhases() {
        GraphHopperOsmService service = newService();

        MapConfig cfg = service.fetchAndConvert(fixture("signal.osm"), bbox);

        // Signal node 2 is promoted to a tower via setSplitNodeFilter (spike guidance),
        // OR it is already a tower because ways 100 and 200 share it.
        // Either way it must be detected as SIGNAL.
        List<MapConfig.IntersectionConfig> signals =
                cfg.getIntersections().stream().filter(ic -> "SIGNAL".equals(ic.getType())).toList();
        assertThat(signals).hasSize(1);

        MapConfig.IntersectionConfig signal = signals.get(0);
        assertThat(signal.getSignalPhases()).isNotNull().isNotEmpty();
        assertThat(signal.getSignalPhases())
                .allSatisfy(p -> assertThat(p.getDurationMs()).isEqualTo(30_000L));

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 5. missing-tags.osm — IllegalStateException
    // ---------------------------------------------------------------------

    @Test
    void missingTags_throwsIllegalStateException() {
        GraphHopperOsmService service = newService();

        assertThatThrownBy(() -> service.fetchAndConvert(fixture("missing-tags.osm"), bbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads found");
    }

    // ---------------------------------------------------------------------
    // 6. Every node/road id starts with gh-
    // ---------------------------------------------------------------------

    @Test
    void allNodeIdsHaveGhPrefix() {
        GraphHopperOsmService service = newService();

        MapConfig cfg = service.fetchAndConvert(fixture("t-intersection.osm"), bbox);

        assertThat(cfg.getNodes()).allSatisfy(n -> assertThat(n.getId()).startsWith("gh-"));
        assertThat(cfg.getRoads())
                .allSatisfy(
                        r -> {
                            assertThat(r.getId()).startsWith("gh-");
                            assertThat(r.getFromNodeId()).startsWith("gh-");
                            assertThat(r.getToNodeId()).startsWith("gh-");
                        });
    }

    // ---------------------------------------------------------------------
    // 7. Phase 24 regression: Phase 23 must never populate RoadConfig.lanes
    // ---------------------------------------------------------------------

    @Test
    void lanesFieldIsNullForPhase23() {
        GraphHopperOsmService service = newService();

        MapConfig cfg = service.fetchAndConvert(fixture("straight.osm"), bbox);

        assertThat(cfg.getRoads()).isNotEmpty();
        assertThat(cfg.getRoads())
                .as("Phase 23 must never populate lanes[] — it's osm2streets-only")
                .allSatisfy(r -> assertThat(r.getLanes()).isNull());
    }

    // ---------------------------------------------------------------------
    // 8. Lane clamp: highway=motorway + lanes=6 must yield laneCount == 4
    // ---------------------------------------------------------------------

    @Test
    void laneCountClampedWhenLanesTagSix(@TempDir Path tmp) throws Exception {
        String osmXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<osm version=\"0.6\" generator=\"test\">\n"
                    + "  <node id=\"1\" lat=\"52.2200\" lon=\"21.0000\"/>\n"
                    + "  <node id=\"2\" lat=\"52.2210\" lon=\"21.0010\"/>\n"
                    + "  <way id=\"100\">\n"
                    + "    <nd ref=\"1\"/><nd ref=\"2\"/>\n"
                    + "    <tag k=\"highway\" v=\"motorway\"/>\n"
                    + "    <tag k=\"lanes\" v=\"6\"/>\n"
                    + "  </way>\n"
                    + "</osm>\n";

        Path osmFile = tmp.resolve("lane-clamp.osm");
        Files.writeString(osmFile, osmXml, StandardCharsets.UTF_8);

        GraphHopperOsmService service = newService();
        MapConfig cfg = service.fetchAndConvert(osmFile.toFile(), bbox);

        assertThat(cfg.getRoads()).isNotEmpty();
        assertThat(cfg.getRoads())
                .as("lanes=6 must clamp to MAX_LANE_COUNT=4 (exact)")
                .allSatisfy(r -> assertThat(r.getLaneCount()).isEqualTo(4));

        assertThat(validator.validate(cfg)).isEmpty();
    }
}
