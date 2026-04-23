package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Unit tests for {@link StreetNetworkMapper} — Phase 24 Plan 24-04.
 *
 * <p>Drives each of the 6 canned StreetNetwork JSON fixtures from Plan 24-01 through the mapper
 * and asserts the resulting {@link MapConfig} is MapValidator-clean plus carries the expected
 * per-fixture invariants (lane types, intersection controls, empty-input handling, lane-count
 * clamp, tagged-vs-string LaneType handling, MVP-type filter).
 */
class StreetNetworkMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MapValidator validator = new MapValidator();

    // Fixture bboxes from observed gps_bounds (Plan 24-01 SUMMARY "Per-fixture regression baseline").
    private final BboxRequest straightBbox = new BboxRequest(52.22, 21.0, 52.221, 21.001);
    private final BboxRequest tIntersectionBbox = new BboxRequest(52.22, 21.0, 52.222, 21.002);
    private final BboxRequest roundaboutBbox = new BboxRequest(52.218, 20.998, 52.224, 21.004);
    private final BboxRequest signalBbox = new BboxRequest(52.22, 21.0, 52.222, 21.002);
    private final BboxRequest bikeLaneBbox = new BboxRequest(52.22, 21.0, 52.221, 21.001);
    private final BboxRequest missingTagsBbox = new BboxRequest(52.22, 21.0, 52.221, 21.001);

    private JsonNode loadFixture(String name) throws IOException {
        Path p = Paths.get("src/test/resources/osm2streets/" + name + "-streetnetwork.json");
        if (!Files.exists(p)) {
            p = Paths.get("backend/src/test/resources/osm2streets/" + name + "-streetnetwork.json");
        }
        return mapper.readTree(Files.readString(p));
    }

    // ---------------------------------------------------------------------
    // M1 — straight fixture
    // ---------------------------------------------------------------------

    @Test
    void m1_straight_producesValidMapConfigWithLanes() throws IOException {
        JsonNode network = loadFixture("straight");

        MapConfig cfg = StreetNetworkMapper.map(network, straightBbox);

        assertThat(cfg.getRoads()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(cfg.getRoads())
                .allSatisfy(r -> assertThat(r.getLaneCount()).isBetween(1, 4));
        assertThat(cfg.getRoads()).allSatisfy(r -> assertThat(r.getLanes()).isNotEmpty());
        assertThat(cfg.getRoads()).allSatisfy(r -> assertThat(r.getId()).startsWith("o2s-"));
        assertThat(cfg.getNodes()).allSatisfy(n -> assertThat(n.getId()).startsWith("o2s-"));

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // M2 — bike-lane fixture carries cycling lane metadata
    // ---------------------------------------------------------------------

    @Test
    void m2_bikeLane_roadContainsCyclingLaneEntry() throws IOException {
        JsonNode network = loadFixture("bike-lane");

        MapConfig cfg = StreetNetworkMapper.map(network, bikeLaneBbox);

        boolean hasCycling =
                cfg.getRoads().stream()
                        .flatMap(r -> r.getLanes() == null ? java.util.stream.Stream.empty() : r.getLanes().stream())
                        .anyMatch(l -> "cycling".equals(l.getType()));
        assertThat(hasCycling)
                .as("bike-lane fixture must emit at least one LaneConfig of type=cycling")
                .isTrue();

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // M3 — signal fixture produces a SIGNAL intersection with signalPhases
    // ---------------------------------------------------------------------

    @Test
    void m3_signal_producesSignalIntersectionWithPhases() throws IOException {
        JsonNode network = loadFixture("signal");

        MapConfig cfg = StreetNetworkMapper.map(network, signalBbox);

        List<MapConfig.IntersectionConfig> signals =
                cfg.getIntersections() == null
                        ? List.of()
                        : cfg.getIntersections().stream()
                                .filter(ic -> "SIGNAL".equals(ic.getType()))
                                .toList();
        assertThat(signals)
                .as("signal fixture must produce at least one SIGNAL intersection")
                .hasSizeGreaterThanOrEqualTo(1);
        assertThat(signals)
                .allSatisfy(
                        ic -> assertThat(ic.getSignalPhases()).as("SIGNAL must have phases")
                                .isNotNull()
                                .isNotEmpty());

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // M4 — t-intersection fixture: 2+ roads, 1+ intersection (PRIORITY acceptable)
    // ---------------------------------------------------------------------

    @Test
    void m4_tIntersection_producesRoadsAndIntersection() throws IOException {
        JsonNode network = loadFixture("t-intersection");

        MapConfig cfg = StreetNetworkMapper.map(network, tIntersectionBbox);

        assertThat(cfg.getRoads()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(cfg.getIntersections()).isNotNull();
        assertThat(cfg.getIntersections()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(cfg.getIntersections())
                .allSatisfy(ic -> assertThat(ic.getType()).isIn("PRIORITY", "SIGNAL", "ROUNDABOUT"));

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // M5 — roundabout fixture: does NOT throw; PRIORITY (roundabout gap — RESEARCH Pitfall 5)
    // ---------------------------------------------------------------------

    @Test
    void m5_roundabout_doesNotThrowAndEmitsPriorityIntersections() throws IOException {
        JsonNode network = loadFixture("roundabout");

        MapConfig cfg = StreetNetworkMapper.map(network, roundaboutBbox);

        // Known gap: osm2streets' Signed/Uncontrolled controls do not preserve the OSM
        // junction=roundabout tag; the mapper maps every non-Signalled control to PRIORITY.
        // Documented in 24-04-SUMMARY.md "Roundabout detection gap".
        assertThat(cfg.getIntersections()).isNotNull();
        assertThat(cfg.getIntersections())
                .allSatisfy(ic -> assertThat(ic.getType()).isIn("PRIORITY", "SIGNAL"));

        assertThat(validator.validate(cfg)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // M6 — missing-tags fixture: empty roads -> IllegalStateException
    // ---------------------------------------------------------------------

    @Test
    void m6_missingTags_throwsIllegalStateException() throws IOException {
        JsonNode network = loadFixture("missing-tags");

        assertThatThrownBy(() -> StreetNetworkMapper.map(network, missingTagsBbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads");
    }

    // ---------------------------------------------------------------------
    // M7 — lane clamp: 8 Driving lanes on crafted JSON -> laneCount=4 (clamp) + lanes[] has 8 entries
    // ---------------------------------------------------------------------

    @Test
    void m7_laneClamp_eightDrivingLanesProduceLaneCountFourAndFullLaneList() throws IOException {
        String json =
                "{"
                        + "\"roads\":[[0,{"
                        + "\"id\":0,\"osm_ids\":[1],\"src_i\":0,\"dst_i\":1,"
                        + "\"highway_type\":\"primary\",\"name\":null,"
                        + "\"internal_junction_road\":false,\"layer\":0,\"speed_limit\":null,"
                        + "\"reference_line\":{\"pts\":[{\"x\":0,\"y\":0},{\"x\":500000,\"y\":0}],\"length\":500000},"
                        + "\"reference_line_placement\":{\"Consistent\":\"Center\"},"
                        + "\"center_line\":{\"pts\":[{\"x\":0,\"y\":0},{\"x\":500000,\"y\":0}],\"length\":500000},"
                        + "\"trim_start\":0,\"trim_end\":0,\"turn_restrictions\":[],\"complicated_turn_restrictions\":[],"
                        + "\"lane_specs_ltr\":["
                        + "{\"lt\":\"Driving\",\"dir\":\"Forward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Forward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Forward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Forward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Backward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Backward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Backward\",\"width\":30000},"
                        + "{\"lt\":\"Driving\",\"dir\":\"Backward\",\"width\":30000}"
                        + "],"
                        + "\"stop_line_start\":{\"interruption\":\"Uninterrupted\"},"
                        + "\"stop_line_end\":{\"interruption\":\"Uninterrupted\"}"
                        + "}]],"
                        + "\"intersections\":["
                        + "[0,{\"id\":0,\"osm_ids\":[],\"polygon\":{\"rings\":[]},"
                        + "\"kind\":\"Terminus\",\"control\":\"Signed\",\"roads\":[0],\"movements\":[],"
                        + "\"crossing\":null,\"trim_roads_for_merging\":[]}],"
                        + "[1,{\"id\":1,\"osm_ids\":[],\"polygon\":{\"rings\":[]},"
                        + "\"kind\":\"Terminus\",\"control\":\"Signed\",\"roads\":[0],\"movements\":[],"
                        + "\"crossing\":null,\"trim_roads_for_merging\":[]}]"
                        + "],"
                        + "\"gps_bounds\":{\"min_lon\":21.0,\"min_lat\":52.22,\"max_lon\":21.001,\"max_lat\":52.221}"
                        + "}";

        JsonNode network = mapper.readTree(json);
        MapConfig cfg = StreetNetworkMapper.map(network, straightBbox);

        assertThat(cfg.getRoads()).isNotEmpty();
        assertThat(cfg.getRoads())
                .as("8 Driving lanes must clamp laneCount to MAX_LANE_COUNT=4")
                .allSatisfy(r -> assertThat(r.getLaneCount()).isEqualTo(4));
        // lanes[] keeps full fidelity — all 8 driving entries preserved (clamp applies only to laneCount).
        assertThat(cfg.getRoads())
                .allSatisfy(r -> assertThat(r.getLanes()).hasSize(8));
    }

    // ---------------------------------------------------------------------
    // M8 — MVP type filter: Shoulder + Bus entries dropped, only driving preserved
    // ---------------------------------------------------------------------

    @Test
    void m8_mvpTypeFilter_shoulderAndBusAreDropped() throws IOException {
        String json =
                "{"
                        + "\"roads\":[[0,{"
                        + "\"id\":0,\"osm_ids\":[1],\"src_i\":0,\"dst_i\":1,"
                        + "\"highway_type\":\"primary\",\"name\":null,"
                        + "\"internal_junction_road\":false,\"layer\":0,\"speed_limit\":null,"
                        + "\"reference_line\":{\"pts\":[{\"x\":0,\"y\":0},{\"x\":500000,\"y\":0}],\"length\":500000},"
                        + "\"reference_line_placement\":{\"Consistent\":\"Center\"},"
                        + "\"center_line\":{\"pts\":[{\"x\":0,\"y\":0},{\"x\":500000,\"y\":0}],\"length\":500000},"
                        + "\"trim_start\":0,\"trim_end\":0,\"turn_restrictions\":[],\"complicated_turn_restrictions\":[],"
                        + "\"lane_specs_ltr\":["
                        + "{\"lt\":\"Driving\",\"dir\":\"Forward\",\"width\":30000},"
                        + "{\"lt\":\"Shoulder\",\"dir\":\"Forward\",\"width\":10000},"
                        + "{\"lt\":\"Bus\",\"dir\":\"Forward\",\"width\":30000}"
                        + "],"
                        + "\"stop_line_start\":{\"interruption\":\"Uninterrupted\"},"
                        + "\"stop_line_end\":{\"interruption\":\"Uninterrupted\"}"
                        + "}]],"
                        + "\"intersections\":["
                        + "[0,{\"id\":0,\"osm_ids\":[],\"polygon\":{\"rings\":[]},"
                        + "\"kind\":\"Terminus\",\"control\":\"Signed\",\"roads\":[0],\"movements\":[],"
                        + "\"crossing\":null,\"trim_roads_for_merging\":[]}],"
                        + "[1,{\"id\":1,\"osm_ids\":[],\"polygon\":{\"rings\":[]},"
                        + "\"kind\":\"Terminus\",\"control\":\"Signed\",\"roads\":[0],\"movements\":[],"
                        + "\"crossing\":null,\"trim_roads_for_merging\":[]}]"
                        + "],"
                        + "\"gps_bounds\":{\"min_lon\":21.0,\"min_lat\":52.22,\"max_lon\":21.001,\"max_lat\":52.221}"
                        + "}";

        JsonNode network = mapper.readTree(json);
        MapConfig cfg = StreetNetworkMapper.map(network, straightBbox);

        assertThat(cfg.getRoads()).hasSize(1);
        MapConfig.RoadConfig r = cfg.getRoads().get(0);
        assertThat(r.getLanes())
                .as("only MVP types (driving/parking/cycling/sidewalk) survive — Shoulder & Bus dropped")
                .hasSize(1);
        assertThat(r.getLanes().get(0).getType()).isEqualTo("driving");
    }

    // ---------------------------------------------------------------------
    // M9 — width defensive parsing: accept flat i32 AND tagged-object LaneType (Parking)
    // ---------------------------------------------------------------------

    @Test
    void m9_taggedLaneTypeVariant_handledAsString() throws IOException {
        // Parking is a tagged enum: {"Parking":"Parallel"} — mapper must extract the key name.
        // width is the flat i32 scaled by 10_000 (Plan 24-01 SUMMARY: 30000 -> 3.0 m).
        String json =
                "{"
                        + "\"roads\":[[0,{"
                        + "\"id\":0,\"osm_ids\":[1],\"src_i\":0,\"dst_i\":1,"
                        + "\"highway_type\":\"residential\",\"name\":null,"
                        + "\"internal_junction_road\":false,\"layer\":0,\"speed_limit\":null,"
                        + "\"reference_line\":{\"pts\":[{\"x\":0,\"y\":0},{\"x\":500000,\"y\":0}],\"length\":500000},"
                        + "\"reference_line_placement\":{\"Consistent\":\"Center\"},"
                        + "\"center_line\":{\"pts\":[{\"x\":0,\"y\":0},{\"x\":500000,\"y\":0}],\"length\":500000},"
                        + "\"trim_start\":0,\"trim_end\":0,\"turn_restrictions\":[],\"complicated_turn_restrictions\":[],"
                        + "\"lane_specs_ltr\":["
                        + "{\"lt\":\"Driving\",\"dir\":\"Forward\",\"width\":30000},"
                        + "{\"lt\":{\"Parking\":\"Parallel\"},\"dir\":\"Backward\",\"width\":25000}"
                        + "],"
                        + "\"stop_line_start\":{\"interruption\":\"Uninterrupted\"},"
                        + "\"stop_line_end\":{\"interruption\":\"Uninterrupted\"}"
                        + "}]],"
                        + "\"intersections\":["
                        + "[0,{\"id\":0,\"osm_ids\":[],\"polygon\":{\"rings\":[]},"
                        + "\"kind\":\"Terminus\",\"control\":\"Signed\",\"roads\":[0],\"movements\":[],"
                        + "\"crossing\":null,\"trim_roads_for_merging\":[]}],"
                        + "[1,{\"id\":1,\"osm_ids\":[],\"polygon\":{\"rings\":[]},"
                        + "\"kind\":\"Terminus\",\"control\":\"Signed\",\"roads\":[0],\"movements\":[],"
                        + "\"crossing\":null,\"trim_roads_for_merging\":[]}]"
                        + "],"
                        + "\"gps_bounds\":{\"min_lon\":21.0,\"min_lat\":52.22,\"max_lon\":21.001,\"max_lat\":52.221}"
                        + "}";

        JsonNode network = mapper.readTree(json);
        MapConfig cfg = StreetNetworkMapper.map(network, straightBbox);

        MapConfig.RoadConfig r = cfg.getRoads().get(0);
        assertThat(r.getLanes()).hasSize(2);

        List<String> laneTypes = r.getLanes().stream().map(MapConfig.LaneConfig::getType).toList();
        assertThat(laneTypes).containsExactlyInAnyOrder("driving", "parking");

        // Both widths are flat i32 (scaled) — 30000 -> 3.0 m, 25000 -> 2.5 m.
        List<Double> widths = r.getLanes().stream().map(MapConfig.LaneConfig::getWidth).toList();
        assertThat(widths).containsExactlyInAnyOrder(3.0, 2.5);
    }
}
