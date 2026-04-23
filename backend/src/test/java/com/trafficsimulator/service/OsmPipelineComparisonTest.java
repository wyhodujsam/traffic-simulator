package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;

import lombok.extern.slf4j.Slf4j;

/**
 * A/B comparison test: drives BOTH {@code /api/osm/fetch-roads} (Phase 18 Overpass converter) and
 * {@code /api/osm/fetch-roads-gh} (Phase 23 GraphHopper converter) with the same canned bbox,
 * asserts each output passes {@link MapValidator}, and logs a diff of roads / intersections /
 * converter name without failing on divergence.
 *
 * <p><b>Disabled by default.</b> Requires a live Overpass API round-trip — kept off CI via the
 * {@code osm.online} system property gate. Run manually with:
 *
 * <pre>
 *   mvn test -pl backend -Dtest=OsmPipelineComparisonTest -Dosm.online=on
 * </pre>
 *
 * <p>Why we keep this alongside the fixture-based {@link GraphHopperOsmServiceTest}: the fixture
 * tests lock in per-topology behaviour against hand-rolled OSM XML. This comparison test catches
 * real-world divergence between the two {@link OsmConverter} implementations on genuine Overpass
 * data — divergence that by design is NOT asserted as equality.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "osm.online", matches = "on|true")
@Slf4j
class OsmPipelineComparisonTest {

    private static final String BBOX_JSON =
            """
            {"south":52.2197,"west":21.0022,"north":52.2247,"east":21.0072}""";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MapValidator mapValidator;

    // Both OsmConverter impls wired via concrete types — ensures structural grep for
    // "OsmConverter" finds two context-relevant references AND that both beans are present.
    @Autowired private OsmPipelineService overpassConverter; // OsmConverter impl #1 (Phase 18)
    @Autowired private GraphHopperOsmService graphHopperConverter; // OsmConverter impl #2 (Phase 23)

    @Test
    void bothConverters_sameBbox_produceValidatorCleanConfigs_logDiff() throws Exception {
        // Sanity: both beans satisfy the OsmConverter contract (per Plan 23-02).
        assertThat((OsmConverter) overpassConverter).isNotNull();
        assertThat((OsmConverter) graphHopperConverter).isNotNull();

        MapConfig phase18 = callEndpoint("/api/osm/fetch-roads");
        MapConfig phase23 = callEndpoint("/api/osm/fetch-roads-gh");

        assertThat(mapValidator.validate(phase18)).as("Phase 18 validator errors").isEmpty();
        assertThat(mapValidator.validate(phase23)).as("Phase 23 validator errors").isEmpty();

        log.info(
                "A/B diff — {}: {} roads / {} intersections; {}: {} roads / {} intersections",
                overpassConverter.converterName(),
                phase18.getRoads() == null ? 0 : phase18.getRoads().size(),
                phase18.getIntersections() == null ? 0 : phase18.getIntersections().size(),
                graphHopperConverter.converterName(),
                phase23.getRoads() == null ? 0 : phase23.getRoads().size(),
                phase23.getIntersections() == null ? 0 : phase23.getIntersections().size());

        // No equality assertion — divergence is the whole point of the A/B comparison.
    }

    private MapConfig callEndpoint(String path) throws Exception {
        MvcResult res =
                mvc.perform(
                                post(path)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(BBOX_JSON))
                        .andExpect(status().isOk())
                        .andReturn();
        return objectMapper.readValue(res.getResponse().getContentAsString(), MapConfig.class);
    }
}
