package com.trafficsimulator.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.springframework.http.MediaType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.multipart.MultipartFile;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.service.ClaudeVisionService;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliException;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliParseException;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliTimeoutException;
import com.trafficsimulator.service.ComponentVisionService;
import com.trafficsimulator.service.OsmStaticMapService;

@WebMvcTest(VisionController.class)
class VisionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ClaudeVisionService claudeVisionService;
    @MockBean private OsmStaticMapService osmStaticMapService;
    @MockBean private ComponentVisionService componentVisionService;

    // -------------------------------------------------------------------------
    // Helper: minimal valid MapConfig
    // -------------------------------------------------------------------------

    private static MapConfig minimalMapConfig() {
        MapConfig config = new MapConfig();
        config.setId("vision-generated");
        config.setName("AI Generated Map");

        MapConfig.NodeConfig n1 = new MapConfig.NodeConfig();
        n1.setId("n1");
        n1.setType("ENTRY");
        n1.setX(100.0);
        n1.setY(300.0);

        MapConfig.NodeConfig n2 = new MapConfig.NodeConfig();
        n2.setId("n2");
        n2.setType("EXIT");
        n2.setX(700.0);
        n2.setY(300.0);

        config.setNodes(List.of(n1, n2));

        MapConfig.RoadConfig r1 = new MapConfig.RoadConfig();
        r1.setId("r1");
        r1.setFromNodeId("n1");
        r1.setToNodeId("n2");
        r1.setLength(300.0);
        r1.setSpeedLimit(50.0);
        r1.setLaneCount(2);
        config.setRoads(List.of(r1));

        MapConfig.SpawnPointConfig sp = new MapConfig.SpawnPointConfig();
        sp.setRoadId("r1");
        sp.setLaneIndex(0);
        sp.setPosition(0.0);
        config.setSpawnPoints(List.of(sp));

        MapConfig.DespawnPointConfig dp = new MapConfig.DespawnPointConfig();
        dp.setRoadId("r1");
        dp.setLaneIndex(0);
        dp.setPosition(300.0);
        config.setDespawnPoints(List.of(dp));

        return config;
    }

    // -------------------------------------------------------------------------
    // Test 1: valid PNG upload → 200 with MapConfig JSON
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_validPng_returns200WithMapConfig() throws Exception {
        when(claudeVisionService.analyzeImage(any(MultipartFile.class)))
                .thenReturn(minimalMapConfig());

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/vision/analyze").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("vision-generated"))
                .andExpect(jsonPath("$.name").value("AI Generated Map"));
    }

    // -------------------------------------------------------------------------
    // Test 2: empty file → 400
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_emptyFile_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/vision/analyze").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // Test 3: wrong content type → 400
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_nonImageContentType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/vision/analyze").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("JPEG and PNG")));
    }

    // -------------------------------------------------------------------------
    // Test 4: ClaudeCliTimeoutException → 504
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_timeout_returns504() throws Exception {
        when(claudeVisionService.analyzeImage(any(MultipartFile.class)))
                .thenThrow(new ClaudeCliTimeoutException("timed out"));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/vision/analyze").file(file))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value(containsString("timed out")));
    }

    // -------------------------------------------------------------------------
    // Test 5: ClaudeCliParseException → 422
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_parseError_returns422() throws Exception {
        when(claudeVisionService.analyzeImage(any(MultipartFile.class)))
                .thenThrow(new ClaudeCliParseException("no JSON found"));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/vision/analyze").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("parse")));
    }

    // -------------------------------------------------------------------------
    // Test 6: ClaudeCliException → 503
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_cliUnavailable_returns503() throws Exception {
        when(claudeVisionService.analyzeImage(any(MultipartFile.class)))
                .thenThrow(new ClaudeCliException("binary not found"));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/vision/analyze").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("OSM fetch path")));
    }

    // -------------------------------------------------------------------------
    // Phase 20 regression: /analyze-bbox
    // -------------------------------------------------------------------------

    @Test
    void analyzeBbox_validRequest_returns200WithMapConfig() throws Exception {
        when(osmStaticMapService.composeBboxPng(any())).thenReturn(new byte[]{9, 9, 9});
        when(claudeVisionService.analyzeImageBytes(any(byte[].class)))
                .thenReturn(minimalMapConfig());

        String body = "{\"south\":52.0,\"west\":21.0,\"north\":52.1,\"east\":21.1}";

        mockMvc.perform(MockMvcRequestBuilders.post("/api/vision/analyze-bbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("vision-generated"));
    }

    // -------------------------------------------------------------------------
    // Phase 21: /analyze-components happy path
    // -------------------------------------------------------------------------

    @Test
    void analyzeComponents_validPng_returns200WithMapConfig() throws Exception {
        when(componentVisionService.analyzeImage(any(MultipartFile.class)))
                .thenReturn(minimalMapConfig());

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("vision-generated"))
                .andExpect(jsonPath("$.name").value("AI Generated Map"));
    }

    @Test
    void analyzeComponents_emptyFile_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void analyzeComponents_nonImageContentType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("JPEG and PNG")));
    }

    @Test
    void analyzeComponents_tooLarge_returns400() throws Exception {
        byte[] huge = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("image", "big.png", "image/png", huge);

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("10MB")));
    }

    @Test
    void analyzeComponents_parseError_returns422() throws Exception {
        when(componentVisionService.analyzeImage(any(MultipartFile.class)))
                .thenThrow(new ClaudeCliParseException("bad component spec"));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("parse")));
    }

    @Test
    void analyzeComponents_cliUnavailable_returns503() throws Exception {
        when(componentVisionService.analyzeImage(any(MultipartFile.class)))
                .thenThrow(new ClaudeCliException("binary not found"));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("OSM fetch path")));
    }

    @Test
    void analyzeComponents_timeout_returns504() throws Exception {
        when(componentVisionService.analyzeImage(any(MultipartFile.class)))
                .thenThrow(new ClaudeCliTimeoutException("timed out"));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/vision/analyze-components").file(file))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value(containsString("timed out")));
    }

    // -------------------------------------------------------------------------
    // Phase 21: /analyze-components-bbox happy path
    // -------------------------------------------------------------------------

    @Test
    void analyzeComponentsBbox_validRequest_returns200WithMapConfig() throws Exception {
        when(osmStaticMapService.composeBboxPng(any())).thenReturn(new byte[]{7, 7, 7});
        when(componentVisionService.analyzeImageBytes(any(byte[].class)))
                .thenReturn(minimalMapConfig());

        String body = "{\"south\":52.0,\"west\":21.0,\"north\":52.1,\"east\":21.1}";

        mockMvc.perform(MockMvcRequestBuilders.post("/api/vision/analyze-components-bbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("vision-generated"));
    }
}
