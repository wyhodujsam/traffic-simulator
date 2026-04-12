package com.trafficsimulator.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.web.client.RestClientException;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;
import com.trafficsimulator.service.OsmPipelineService;

@WebMvcTest(OsmController.class)
class OsmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OsmPipelineService osmPipelineService;

    private static final String VALID_BBOX_JSON = """
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

        mockMvc.perform(post("/api/osm/fetch-roads")
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

        mockMvc.perform(post("/api/osm/fetch-roads")
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

        mockMvc.perform(post("/api/osm/fetch-roads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BBOX_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("unavailable")));
    }
}
