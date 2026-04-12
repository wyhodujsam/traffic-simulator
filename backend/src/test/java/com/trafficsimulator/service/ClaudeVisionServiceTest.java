package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.ClaudeCliConfig;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliParseException;

@ExtendWith(MockitoExtension.class)
class ClaudeVisionServiceTest {

    @Mock private ClaudeCliConfig config;
    @Mock private MapValidator mapValidator;

    private ObjectMapper objectMapper;
    private ClaudeVisionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ClaudeVisionService(config, mapValidator, objectMapper);
    }

    // -------------------------------------------------------------------------
    // Test 1: ANALYSIS_PROMPT contains key MapConfig field names
    // -------------------------------------------------------------------------

    @Test
    void analysisPrompt_containsAllRequiredFieldNames() {
        String prompt = ClaudeVisionService.ANALYSIS_PROMPT;
        assertThat(prompt).contains("nodes");
        assertThat(prompt).contains("roads");
        assertThat(prompt).contains("intersections");
        assertThat(prompt).contains("spawnPoints");
        assertThat(prompt).contains("despawnPoints");
    }

    @Test
    void analysisPrompt_instructsJsonOnlyOutput() {
        String prompt = ClaudeVisionService.ANALYSIS_PROMPT;
        assertThat(prompt).containsIgnoringCase("no markdown");
        assertThat(prompt).containsIgnoringCase("only valid json");
    }

    // -------------------------------------------------------------------------
    // Test 2: extractJson handles preamble + JSON + postamble
    // -------------------------------------------------------------------------

    @Test
    void extractJson_withPreambleAndPostamble_returnsOnlyJson() {
        String input = "Here is your JSON response:\n{\"id\":\"test\",\"name\":\"x\"}\nDone.";
        String result = service.extractJson(input);
        assertThat(result).isEqualTo("{\"id\":\"test\",\"name\":\"x\"}");
    }

    @Test
    void extractJson_pureJson_returnsSame() {
        String input = "{\"key\":\"value\"}";
        String result = service.extractJson(input);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void extractJson_noJsonPresent_throwsParseException() {
        assertThatThrownBy(() -> service.extractJson("no json here"))
                .isInstanceOf(ClaudeCliParseException.class)
                .hasMessageContaining("Could not find JSON");
    }

    @Test
    void extractJson_emptyString_throwsParseException() {
        assertThatThrownBy(() -> service.extractJson(""))
                .isInstanceOf(ClaudeCliParseException.class);
    }

    // -------------------------------------------------------------------------
    // Test 3: getExtension returns correct extensions
    // -------------------------------------------------------------------------

    @Test
    void getExtension_png_returnsDotPng() {
        assertThat(service.getExtension("road.png")).isEqualTo(".png");
    }

    @Test
    void getExtension_jpg_returnsDotJpg() {
        assertThat(service.getExtension("photo.jpg")).isEqualTo(".jpg");
    }

    @Test
    void getExtension_jpeg_returnsDotJpg() {
        assertThat(service.getExtension("photo.JPEG")).isEqualTo(".jpg");
    }

    @Test
    void getExtension_null_returnsDotPng() {
        assertThat(service.getExtension(null)).isEqualTo(".png");
    }

    @Test
    void getExtension_unknownExtension_returnsDotPng() {
        assertThat(service.getExtension("file.bmp")).isEqualTo(".png");
    }

    // -------------------------------------------------------------------------
    // Test 4: analyzeImage happy path via spy (mocked executeCliCommand)
    // -------------------------------------------------------------------------

    @Test
    void analyzeImage_happyPath_returnsValidatedMapConfig() throws IOException {
        // Arrange: a valid MapConfig JSON
        String mapConfigJson = buildMinimalMapConfigJson();

        when(config.getTempDir()).thenReturn(System.getProperty("java.io.tmpdir"));
        when(mapValidator.validate(any(MapConfig.class))).thenReturn(List.of());

        ClaudeVisionService spy = spy(service);
        doReturn(mapConfigJson).when(spy).executeCliCommand(any(String[].class));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        // Act
        MapConfig result = spy.analyzeImage(file);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("vision-generated");
        assertThat(result.getNodes()).hasSize(2);
        assertThat(result.getRoads()).hasSize(1);
    }

    @Test
    void analyzeImage_validationErrors_throwsParseException() throws IOException {
        String mapConfigJson = buildMinimalMapConfigJson();

        when(config.getTempDir()).thenReturn(System.getProperty("java.io.tmpdir"));
        when(mapValidator.validate(any(MapConfig.class)))
                .thenReturn(List.of("Road r1 length must be positive"));

        ClaudeVisionService spy = spy(service);
        doReturn(mapConfigJson).when(spy).executeCliCommand(any(String[].class));

        MockMultipartFile file = new MockMultipartFile(
                "image", "road.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> spy.analyzeImage(file))
                .isInstanceOf(ClaudeCliParseException.class)
                .hasMessageContaining("validation errors");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildMinimalMapConfigJson() {
        return """
                {
                  "id": "vision-generated",
                  "name": "AI Generated Map",
                  "defaultSpawnRate": 1.0,
                  "nodes": [
                    {"id": "n1", "type": "ENTRY", "x": 100.0, "y": 300.0},
                    {"id": "n2", "type": "EXIT", "x": 700.0, "y": 300.0}
                  ],
                  "roads": [
                    {"id": "r1", "fromNodeId": "n1", "toNodeId": "n2",
                     "length": 300.0, "speedLimit": 50.0, "laneCount": 2}
                  ],
                  "spawnPoints": [{"roadId": "r1", "laneIndex": 0, "position": 0.0}],
                  "despawnPoints": [{"roadId": "r1", "laneIndex": 0, "position": 300.0}]
                }
                """;
    }
}
