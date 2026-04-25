package com.trafficsimulator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClientException;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;
import com.trafficsimulator.service.GraphHopperOsmService;
import com.trafficsimulator.service.Osm2StreetsService;
import com.trafficsimulator.service.OsmPipelineService;

@WebMvcTest(OsmController.class)
class OsmControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private OsmPipelineService osmPipelineService;
    @MockBean private GraphHopperOsmService graphHopperOsmService;
    @MockBean private Osm2StreetsService osm2StreetsService;

    private static final String VALID_BBOX_JSON =
            """
            {
              "south": 52.2197,
              "west": 21.0022,
              "north": 52.2397,
              "east": 21.0222
            }
            """;

    // -------------------------------------------------------------------------
    // Test 1: valid bbox → 200 with MapConfig JSON (id field exists)
    // -------------------------------------------------------------------------

    @Test
    void fetchRoads_validBbox_returns200WithMapConfig() throws Exception {
        MapConfig config = new MapConfig();
        config.setId("osm-bbox-test");
        config.setName("OSM Import");
        config.setRoads(List.of());
        config.setNodes(List.of());
        when(osmPipelineService.fetchAndConvert(any(BboxRequest.class))).thenReturn(config);

        mockMvc.perform(
                        post("/api/osm/fetch-roads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
    }

    // -------------------------------------------------------------------------
    // Test 2: service throws IllegalStateException → 422 with error body
    // -------------------------------------------------------------------------

    @Test
    void fetchRoads_emptyArea_returns422WithErrorMessage() throws Exception {
        when(osmPipelineService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(new IllegalStateException("No roads found in selected area"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("No roads")));
    }

    // -------------------------------------------------------------------------
    // Test 3: service throws RestClientException → 503 with error body
    // -------------------------------------------------------------------------

    @Test
    void fetchRoads_overpassUnavailable_returns503WithErrorMessage() throws Exception {
        when(osmPipelineService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(new RestClientException("Connection refused"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("unavailable")));
    }

    // -------------------------------------------------------------------------
    // Phase 23 — /fetch-roads-gh endpoint tests (identical taxonomy to /fetch-roads)
    // -------------------------------------------------------------------------

    @Test
    void fetchRoadsGh_validBbox_returns200WithMapConfig() throws Exception {
        MapConfig config = new MapConfig();
        config.setId("gh-bbox-test");
        config.setName("OSM Import");
        config.setRoads(List.of());
        config.setNodes(List.of());
        when(graphHopperOsmService.fetchAndConvert(any(BboxRequest.class))).thenReturn(config);

        mockMvc.perform(
                        post("/api/osm/fetch-roads-gh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void fetchRoadsGh_emptyArea_returns422WithErrorMessage() throws Exception {
        when(graphHopperOsmService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(new IllegalStateException("No roads found in selected area"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads-gh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("No roads")));
    }

    @Test
    void fetchRoadsGh_overpassUnavailable_returns503WithErrorMessage() throws Exception {
        when(graphHopperOsmService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(new RestClientException("Connection refused"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads-gh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("unavailable")));
    }

    // -------------------------------------------------------------------------
    // Phase 24 — /fetch-roads-o2s endpoint tests (200 / 400 / 422 / 503 / 504)
    // -------------------------------------------------------------------------

    /**
     * W1 — Happy path: valid bbox JSON + mocked service returns a MapConfig → 200 + body.id present.
     */
    @Test
    void fetchRoadsO2s_success_200() throws Exception {
        MapConfig config = new MapConfig();
        config.setId("o2s-bbox-test");
        config.setName("OSM Import (osm2streets)");
        config.setRoads(List.of());
        config.setNodes(List.of());
        when(osm2StreetsService.fetchAndConvert(any(BboxRequest.class))).thenReturn(config);

        mockMvc.perform(
                        post("/api/osm/fetch-roads-o2s")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
    }

    /**
     * W0 — Invalid payload: malformed JSON body produces 400 and the service is never invoked.
     * Mirrors Phase 23 invalid-payload pattern.
     */
    @Test
    void fetchRoadsO2s_invalid_400() throws Exception {
        String malformedJson = "{ this is not valid json";

        mockMvc.perform(
                        post("/api/osm/fetch-roads-o2s")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(malformedJson))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(osm2StreetsService);
    }

    /** W2 — IllegalStateException("No roads…") surfaces as 422 with the error message echoed. */
    @Test
    void fetchRoadsO2s_empty_422() throws Exception {
        when(osm2StreetsService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(new IllegalStateException("No roads found in selected area"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads-o2s")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("No roads")));
    }

    /**
     * W3 — Osm2StreetsCliException (backend-process fault) is mapped to 503 with a generic
     * "unavailable" message (internal error text NOT leaked to clients).
     */
    @Test
    void fetchRoadsO2s_cliError_503() throws Exception {
        when(osm2StreetsService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(
                        new Osm2StreetsService.Osm2StreetsCliException(
                                "osm2streets-cli exited 2: boom"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads-o2s")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("unavailable")));
    }

    /**
     * W4 — Osm2StreetsCliTimeoutException is mapped to 504 with a timeout-specific error message
     * that hints at the bbox-size mitigation.
     */
    @Test
    void fetchRoadsO2s_timeout_504() throws Exception {
        when(osm2StreetsService.fetchAndConvert(any(BboxRequest.class)))
                .thenThrow(
                        new Osm2StreetsService.Osm2StreetsCliTimeoutException(
                                "osm2streets-cli timed out after 30s"));

        mockMvc.perform(
                        post("/api/osm/fetch-roads-o2s")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BBOX_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value(containsString("timed out")));
    }

    // -------------------------------------------------------------------------
    // Byte-identity regression tests — Phase 18 / Phase 23 response body MUST NOT
    // contain the new "lanes" key introduced by the Phase 24 MapConfig extension.
    // (Phase 24-02 made RoadConfig.lanes optional; Phase 18/23 must stay silent on it.)
    // -------------------------------------------------------------------------

    /** W5 — Phase 18 endpoint: response JSON must not serialise a "lanes" key. */
    @Test
    void fetchRoads_phase18_byteIdentity_noLanesKey() throws Exception {
        MapConfig config = new MapConfig();
        config.setId("phase18-byte-identity");
        config.setName("Phase 18 Byte Identity");
        config.setRoads(List.of());
        config.setNodes(List.of());
        when(osmPipelineService.fetchAndConvert(any(BboxRequest.class))).thenReturn(config);

        MvcResult r =
                mockMvc.perform(
                                post("/api/osm/fetch-roads")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(VALID_BBOX_JSON))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"lanes\"");
    }

    /** W6 — Phase 23 endpoint: response JSON must not serialise a "lanes" key. */
    @Test
    void fetchRoadsGh_phase23_byteIdentity_noLanesKey() throws Exception {
        MapConfig config = new MapConfig();
        config.setId("phase23-byte-identity");
        config.setName("Phase 23 Byte Identity");
        config.setRoads(List.of());
        config.setNodes(List.of());
        when(graphHopperOsmService.fetchAndConvert(any(BboxRequest.class))).thenReturn(config);

        MvcResult r =
                mockMvc.perform(
                                post("/api/osm/fetch-roads-gh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(VALID_BBOX_JSON))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"lanes\"");
    }
}
