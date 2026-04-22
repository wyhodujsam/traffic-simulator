package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.ClaudeCliConfig;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliParseException;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliTimeoutException;

/**
 * Unit tests for {@link ComponentVisionService} — prompt sanity, two-pass DTO parsing, CLI error
 * propagation, and end-to-end integration with {@link MapComponentLibrary}.
 *
 * <p>Follows the same idiom as {@code ClaudeVisionServiceTest}: subclass override of
 * {@code executeCliCommand} to inject canned Claude output without invoking the real CLI.
 */
class ComponentVisionServiceTest {

    private ClaudeCliConfig config;
    private MapValidator mapValidator;
    private MapComponentLibrary library;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        config = new ClaudeCliConfig();
        config.setPath("claude");
        config.setTimeoutSeconds(10);
        config.setTempDir(System.getProperty("java.io.tmpdir"));
        mapValidator = new MapValidator();
        library = new MapComponentLibrary(mapValidator);
        objectMapper = new ObjectMapper();
    }

    /** Build a service whose {@code executeCliCommand} returns the canned output. */
    private ComponentVisionService serviceReturning(String cannedOutput) {
        return new ComponentVisionService(config, library, objectMapper) {
            @Override
            String executeCliCommand(String... command) {
                return cannedOutput;
            }
        };
    }

    /** Build a service whose {@code executeCliCommand} throws a prepared exception. */
    private ComponentVisionService serviceThrowing(RuntimeException ex) {
        return new ComponentVisionService(config, library, objectMapper) {
            @Override
            String executeCliCommand(String... command) {
                throw ex;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Prompt sanity
    // -------------------------------------------------------------------------

    @Test
    void prompt_containsAllFourValidComponentTypes() {
        String prompt = ComponentVisionService.ANALYSIS_PROMPT;
        assertThat(prompt).contains("ROUNDABOUT_4ARM");
        assertThat(prompt).contains("SIGNAL_4WAY");
        assertThat(prompt).contains("T_INTERSECTION");
        assertThat(prompt).contains("STRAIGHT_SEGMENT");
    }

    @Test
    void prompt_containsViaductAndHighwayExitRamp() {
        String prompt = ComponentVisionService.ANALYSIS_PROMPT;
        assertThat(prompt).contains("VIADUCT");
        assertThat(prompt).contains("HIGHWAY_EXIT_RAMP");
    }

    @Test
    void prompt_listsArmNamesForNewTypes() {
        String prompt = ComponentVisionService.ANALYSIS_PROMPT;
        assertThat(prompt).contains("main_in");
        assertThat(prompt).contains("main_out");
        assertThat(prompt).contains("ramp_out");
        assertThat(prompt).containsIgnoringCase("no shared intersection");
    }

    @Test
    void prompt_forbidsInventedTypesAndInOutSubstrings() {
        String prompt = ComponentVisionService.ANALYSIS_PROMPT;
        assertThat(prompt).containsIgnoringCase("MUST NOT invent");
        assertThat(prompt).contains("'in'");
        assertThat(prompt).contains("'out'");
    }

    // -------------------------------------------------------------------------
    // Two-pass DTO mapping coverage for all 4 types
    // -------------------------------------------------------------------------

    @Test
    void extractJson_handlesPreambleAndPostamble() {
        ComponentVisionService svc = serviceReturning("ignored");
        String raw = "Here is the output:\n{\"components\":[],\"connections\":[]}\nDone.";
        assertThat(svc.extractJson(raw)).isEqualTo("{\"components\":[],\"connections\":[]}");
    }

    @Test
    void toSpec_roundabout_mapsCorrectly() {
        ComponentVisionService svc = serviceReturning("ignored");
        var dto = new com.trafficsimulator.vision.components.ComponentSpecDto();
        dto.type = "ROUNDABOUT_4ARM";
        dto.id = "rb1";
        dto.centerPx = new java.awt.geom.Point2D.Double(400, 300);
        dto.rotationDeg = 0;
        dto.armsPresent = java.util.List.of("north", "east", "south", "west");
        assertThat(svc.toSpec(dto))
                .isInstanceOf(com.trafficsimulator.vision.components.RoundaboutFourArm.class);
    }

    @Test
    void toSpec_signal_mapsCorrectly() {
        ComponentVisionService svc = serviceReturning("ignored");
        var dto = new com.trafficsimulator.vision.components.ComponentSpecDto();
        dto.type = "SIGNAL_4WAY";
        dto.id = "sig1";
        dto.centerPx = new java.awt.geom.Point2D.Double(400, 300);
        dto.rotationDeg = 0;
        dto.armsPresent = java.util.List.of("north", "east", "south", "west");
        assertThat(svc.toSpec(dto))
                .isInstanceOf(com.trafficsimulator.vision.components.SignalFourWay.class);
    }

    @Test
    void toSpec_tIntersection_mapsCorrectly() {
        ComponentVisionService svc = serviceReturning("ignored");
        var dto = new com.trafficsimulator.vision.components.ComponentSpecDto();
        dto.type = "T_INTERSECTION";
        dto.id = "t1";
        dto.centerPx = new java.awt.geom.Point2D.Double(400, 300);
        dto.rotationDeg = 0;
        dto.armsPresent = java.util.List.of("north", "east", "west");
        assertThat(svc.toSpec(dto))
                .isInstanceOf(com.trafficsimulator.vision.components.TIntersection.class);
    }

    @Test
    void toSpec_viaduct_mapsCorrectly() {
        ComponentVisionService svc = serviceReturning("ignored");
        var dto = new com.trafficsimulator.vision.components.ComponentSpecDto();
        dto.type = "VIADUCT";
        dto.id = "via1";
        dto.centerPx = new java.awt.geom.Point2D.Double(400, 300);
        dto.rotationDeg = 0;
        assertThat(svc.toSpec(dto))
                .isInstanceOf(com.trafficsimulator.vision.components.Viaduct.class);
    }

    @Test
    void toSpec_highwayExitRamp_mapsCorrectly() {
        ComponentVisionService svc = serviceReturning("ignored");
        var dto = new com.trafficsimulator.vision.components.ComponentSpecDto();
        dto.type = "HIGHWAY_EXIT_RAMP";
        dto.id = "hr1";
        dto.centerPx = new java.awt.geom.Point2D.Double(500, 400);
        dto.rotationDeg = 0;
        assertThat(svc.toSpec(dto))
                .isInstanceOf(com.trafficsimulator.vision.components.HighwayExitRamp.class);
    }

    @Test
    void toSpec_straightSegment_mapsCorrectly() {
        ComponentVisionService svc = serviceReturning("ignored");
        var dto = new com.trafficsimulator.vision.components.ComponentSpecDto();
        dto.type = "STRAIGHT_SEGMENT";
        dto.id = "seg1";
        dto.startPx = new java.awt.geom.Point2D.Double(0, 0);
        dto.endPx = new java.awt.geom.Point2D.Double(100, 0);
        dto.lengthPx = 100.0;
        assertThat(svc.toSpec(dto))
                .isInstanceOf(com.trafficsimulator.vision.components.StraightSegment.class);
    }

    // -------------------------------------------------------------------------
    // End-to-end happy path: single roundabout → 12 nodes, 12 roads, 4 intersections
    // -------------------------------------------------------------------------

    @Test
    void analyzeImageBytes_happyPath_singleRoundabout() throws IOException {
        String canned =
                "{\n"
                        + "  \"components\": [\n"
                        + "    {\"type\":\"ROUNDABOUT_4ARM\",\"id\":\"rb1\","
                        + "\"centerPx\":{\"x\":400,\"y\":300},\"rotationDeg\":0,"
                        + "\"armsPresent\":[\"north\",\"east\",\"south\",\"west\"]}\n"
                        + "  ],\n"
                        + "  \"connections\": []\n"
                        + "}";
        ComponentVisionService svc = serviceReturning(canned);

        MapConfig result = svc.analyzeImageBytes(new byte[] {1, 2, 3});

        assertThat(result).isNotNull();
        // 4 ring nodes + 4 ENTRY + 4 EXIT = 12 nodes.
        assertThat(result.getNodes()).hasSize(12);
        // 4 approach (_in) + 4 departure (_out) + 4 ring roads = 12 roads.
        assertThat(result.getRoads()).hasSize(12);
        // 4 ROUNDABOUT intersections (one per ring node).
        assertThat(result.getIntersections()).hasSize(4);
        assertThat(result.getIntersections())
                .allMatch(ic -> "ROUNDABOUT".equals(ic.getType()));
    }

    @Test
    void analyzeImageBytes_viaductStandalone_returnsValidatedMapConfig() throws IOException {
        String canned =
                "{\"components\":[{\"type\":\"VIADUCT\",\"id\":\"via1\","
                        + "\"centerPx\":{\"x\":400,\"y\":300},\"rotationDeg\":0}],"
                        + "\"connections\":[]}";
        ComponentVisionService svc = serviceReturning(canned);

        MapConfig result = svc.analyzeImageBytes(new byte[] {1, 2, 3});

        assertThat(result).isNotNull();
        // 4 arms × (ENTRY + EXIT) = 8 nodes.
        assertThat(result.getNodes()).hasSize(8);
        // 2 pairs of through-roads = 4 one-way roads.
        assertThat(result.getRoads()).hasSize(4);
        // No intersection — overpass has no shared junction.
        assertThat(result.getIntersections()).isEmpty();
    }

    @Test
    void analyzeImageBytes_highwayExitRamp_returnsValidatedMapConfig() throws IOException {
        String canned =
                "{\"components\":[{\"type\":\"HIGHWAY_EXIT_RAMP\",\"id\":\"hr1\","
                        + "\"centerPx\":{\"x\":500,\"y\":400},\"rotationDeg\":0}],"
                        + "\"connections\":[]}";
        ComponentVisionService svc = serviceReturning(canned);

        MapConfig result = svc.analyzeImageBytes(new byte[] {1, 2, 3});

        assertThat(result).isNotNull();
        // 1 centre INTERSECTION + 3 arms × (ENTRY + EXIT) = 7 nodes.
        assertThat(result.getNodes()).hasSize(7);
        // main_in, main_out, ramp_out = 3 roads.
        assertThat(result.getRoads()).hasSize(3);
        assertThat(result.getIntersections()).hasSize(1);
        assertThat(result.getIntersections().get(0).getType()).isEqualTo("PRIORITY");
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    @Test
    void analyzeImageBytes_unknownComponentType_throwsParseException() {
        String canned =
                "{\"components\":[{\"type\":\"CLOVERLEAF_INTERCHANGE\",\"id\":\"cl1\","
                        + "\"centerPx\":{\"x\":1,\"y\":2},\"rotationDeg\":0,"
                        + "\"armsPresent\":[\"north\"]}],\"connections\":[]}";
        ComponentVisionService svc = serviceReturning(canned);

        assertThatThrownBy(() -> svc.analyzeImageBytes(new byte[] {1}))
                .isInstanceOf(ClaudeCliParseException.class)
                .hasMessageContaining("Unknown component type")
                .hasMessageContaining("ROUNDABOUT_4ARM")
                .hasMessageContaining("VIADUCT")
                .hasMessageContaining("HIGHWAY_EXIT_RAMP");
    }

    @Test
    void analyzeImageBytes_badComponentIdWithInSubstring_throwsParseException() {
        // id "train1" contains "in" → MapComponentLibrary.validateId rejects → wrapped.
        String canned =
                "{\"components\":[{\"type\":\"ROUNDABOUT_4ARM\",\"id\":\"train1\","
                        + "\"centerPx\":{\"x\":400,\"y\":300},\"rotationDeg\":0,"
                        + "\"armsPresent\":[\"north\",\"east\",\"south\",\"west\"]}],"
                        + "\"connections\":[]}";
        ComponentVisionService svc = serviceReturning(canned);

        assertThatThrownBy(() -> svc.analyzeImageBytes(new byte[] {1}))
                .isInstanceOf(ClaudeCliParseException.class)
                .hasMessageContaining("train1");
    }

    @Test
    void analyzeImageBytes_explicitConnection_mergesRegardlessOfDistance() throws IOException {
        // Design contract (commit c2e4d59): an explicit Connection is authoritative — the
        // stitcher averages geometry and merges the two arms into a shared INTERSECTION node,
        // no matter how far apart their raw arm endpoints sit. Claude cannot know that a
        // ROUNDABOUT_4ARM arm endpoint lives at center+dir*228px; it signals intent via the
        // connection, and we trust it. (The old "reject if >5px" test has been replaced.)
        String canned =
                "{\"components\":["
                        + "{\"type\":\"ROUNDABOUT_4ARM\",\"id\":\"rb1\","
                        + "\"centerPx\":{\"x\":400,\"y\":300},\"rotationDeg\":0,"
                        + "\"armsPresent\":[\"north\",\"east\",\"south\",\"west\"]},"
                        + "{\"type\":\"ROUNDABOUT_4ARM\",\"id\":\"rb2\","
                        + "\"centerPx\":{\"x\":1200,\"y\":300},\"rotationDeg\":0,"
                        + "\"armsPresent\":[\"north\",\"east\",\"south\",\"west\"]}"
                        + "],\"connections\":[{\"a\":\"rb1.east\",\"b\":\"rb2.west\"}]}";
        ComponentVisionService svc = serviceReturning(canned);

        MapConfig cfg = svc.analyzeImageBytes(new byte[] {1});

        assertThat(cfg).isNotNull();
        assertThat(cfg.getNodes()).anyMatch(n -> n.getId().startsWith("merged__rb1_east__rb2_west"));
    }

    @Test
    void analyzeImageBytes_cliTimeout_propagatesUnwrapped() {
        ComponentVisionService svc =
                serviceThrowing(new ClaudeCliTimeoutException("Analysis timed out after 10 seconds"));

        assertThatThrownBy(() -> svc.analyzeImageBytes(new byte[] {1}))
                .isInstanceOf(ClaudeCliTimeoutException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void analyzeImageBytes_malformedJson_throwsParseException() {
        ComponentVisionService svc = serviceReturning("not json at all");

        assertThatThrownBy(() -> svc.analyzeImageBytes(new byte[] {1}))
                .isInstanceOf(ClaudeCliParseException.class);
    }

    @Test
    void analyzeImageBytes_missingTypeField_throwsParseException() {
        String canned =
                "{\"components\":[{\"id\":\"rb1\",\"centerPx\":{\"x\":1,\"y\":1}}],\"connections\":[]}";
        ComponentVisionService svc = serviceReturning(canned);

        assertThatThrownBy(() -> svc.analyzeImageBytes(new byte[] {1}))
                .isInstanceOf(ClaudeCliParseException.class);
    }

    // -------------------------------------------------------------------------
    // Ensure lib is actually invoked (via spec reference check)
    // -------------------------------------------------------------------------

    @Test
    void analyzeImageBytes_libraryReceivesExpandedSpecs_returnsMapConfig() throws IOException {
        String canned =
                "{\"components\":[{\"type\":\"SIGNAL_4WAY\",\"id\":\"sig1\","
                        + "\"centerPx\":{\"x\":500,\"y\":500},\"rotationDeg\":0,"
                        + "\"armsPresent\":[\"north\",\"east\",\"south\",\"west\"]}],"
                        + "\"connections\":[]}";
        AtomicReference<MapConfig> captured = new AtomicReference<>();
        ComponentVisionService svc = serviceReturning(canned);

        MapConfig result = svc.analyzeImageBytes(new byte[] {1});
        captured.set(result);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getIntersections())
                .extracting(MapConfig.IntersectionConfig::getType)
                .containsExactly("SIGNAL");
    }
}
