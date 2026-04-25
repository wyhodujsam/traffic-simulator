package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.dto.BboxRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * A/B/C comparison harness: drives ALL registered {@link OsmConverter} beans with the same canned
 * bbox, asserts each output passes {@link MapValidator}, and logs a side-by-side diff of roads /
 * intersections / converter name without failing on divergence.
 *
 * <p>Phase 18 (Overpass), Phase 23 (GraphHopper), and Phase 24 (osm2streets) each register as a
 * distinct {@code @Service implements OsmConverter} bean; Spring autowires all three into the
 * {@code List<OsmConverter>} field below. No hard-coded per-converter injection — adding a 4th
 * converter in a future phase only requires a new {@code @Service} class.
 *
 * <p><b>Two tests live here:</b>
 *
 * <ul>
 *   <li>{@link #allThreeConvertersAreRegistered()} — OFFLINE, runs on every CI pass. Asserts
 *       Spring wired exactly the 3 expected converter names (Overpass + GraphHopper + osm2streets).
 *       This is the contract gate — a missing or renamed converter trips this immediately.
 *   <li>{@link #compareAllConverters_sameBbox_logDiff()} — ONLINE, gated by
 *       {@code -Dosm.online=on}. Hits the live Overpass API + (for Phase 24) the local
 *       osm2streets-cli binary. Logs counts side-by-side; never asserts equality — divergence is
 *       the whole point of A/B/C.
 * </ul>
 *
 * <p>Run the online comparison manually:
 *
 * <pre>
 *   mvn test -pl backend -Dtest=OsmPipelineComparisonTest -Dosm.online=on
 * </pre>
 */
@SpringBootTest
@Slf4j
class OsmPipelineComparisonTest {

    /**
     * Warsaw-Centrum bbox (small — keeps the Overpass round-trip + osm2streets CLI fast for manual
     * smoke tests).
     */
    private static final BboxRequest BBOX =
            new BboxRequest(52.2295, 21.0122, 52.2305, 21.0132);

    /**
     * Spring autowires ALL registered {@link OsmConverter} beans into this list. Phase 18
     * ({@link OsmPipelineService}), Phase 23 ({@link GraphHopperOsmService}), and Phase 24
     * ({@link Osm2StreetsService}) each contribute one bean.
     */
    @Autowired private List<OsmConverter> converters;

    @Autowired private MapValidator mapValidator;

    /**
     * OFFLINE contract test — runs on every CI pass. Ensures Spring wired the expected three
     * converters by name. Catches regressions where a converter bean was accidentally demoted from
     * {@code @Service} or renamed.
     */
    @Test
    void allThreeConvertersAreRegistered() {
        assertThat(converters)
                .as("three converters must be registered: Overpass + GraphHopper + osm2streets")
                .hasSize(3);

        Set<String> names =
                converters.stream()
                        .map(OsmConverter::converterName)
                        .collect(Collectors.toSet());
        assertThat(names)
                .containsExactlyInAnyOrder("Overpass", "GraphHopper", "osm2streets");
    }

    /**
     * ONLINE A/B/C comparison — iterates the full converter list, validates each output, and logs
     * a side-by-side diff. Gated by {@code -Dosm.online=on}; skipped on CI by default.
     */
    @Test
    @EnabledIfSystemProperty(named = "osm.online", matches = "on|true")
    void compareAllConverters_sameBbox_logDiff() {
        for (OsmConverter c : converters) {
            if (!c.isAvailable()) {
                log.info("{}: SKIPPED (not available)", c.converterName());
                continue;
            }
            try {
                MapConfig cfg = c.fetchAndConvert(BBOX);
                assertThat(mapValidator.validate(cfg))
                        .as("%s validator errors", c.converterName())
                        .isEmpty();
                log.info(
                        "{}: {} roads, {} intersections",
                        c.converterName(),
                        cfg.getRoads() == null ? 0 : cfg.getRoads().size(),
                        cfg.getIntersections() == null ? 0 : cfg.getIntersections().size());
            } catch (Exception e) {
                // Never fail on divergence / per-converter error; the online comparison is
                // deliberately best-effort so one fragile converter doesn't mask the others.
                log.warn("{}: FAILED - {}", c.converterName(), e.getMessage());
            }
        }
    }
}
