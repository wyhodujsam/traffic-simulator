package com.trafficsimulator.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.service.ClaudeVisionService;
import com.trafficsimulator.service.ComponentVisionService;

/**
 * Diagnostic opt-in harness: runs both Phase 20 ({@link ClaudeVisionService}) and Phase 21
 * ({@link ComponentVisionService}) pipelines on the canonical roundabout fixture
 * ({@code /tmp/roundabout-test.png}) and dumps both {@link MapConfig} outputs plus a Markdown diff
 * report to {@code backend/target/vision-comparison/}.
 *
 * <p>Gated by {@code -Dvision.harness=true} so the default Maven build never invokes the real
 * Claude CLI. Missing fixture triggers a JUnit {@link Assumptions} skip, not a failure. If either
 * pipeline throws, the exception is recorded in the diff report and the other pipeline still runs
 * — this harness is diagnostic, never regressive.
 *
 * <p>Manual invocation:
 * <pre>cd backend && ./mvnw -Dvision.harness=true -Dtest=VisionComparisonHarness test</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "vision.harness", matches = "true")
class VisionComparisonHarness {

    private static final Path FIXTURE = Path.of("/tmp/roundabout-test.png");
    private static final Path OUTPUT_DIR = Path.of("target/vision-comparison");

    @Autowired private ClaudeVisionService phase20;
    @Autowired private ComponentVisionService phase21;
    @Autowired private MapValidator validator;

    @Test
    void compareOnRoundaboutFixture() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(FIXTURE),
                "Fixture " + FIXTURE + " not present; harness skipped.");

        byte[] png = Files.readAllBytes(FIXTURE);
        Map<String, String> exceptions = new LinkedHashMap<>();

        MapConfig freeForm = safeCall("free-form", () -> phase20.analyzeImageBytes(png), exceptions);
        MapConfig components =
                safeCall("components", () -> phase21.analyzeImageBytes(png), exceptions);

        Files.createDirectories(OUTPUT_DIR);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        if (freeForm != null) {
            mapper.writeValue(OUTPUT_DIR.resolve("free-form.json").toFile(), freeForm);
        }
        if (components != null) {
            mapper.writeValue(OUTPUT_DIR.resolve("components.json").toFile(), components);
        }

        String report = buildDiffReport(png.length, freeForm, components, exceptions);
        Files.writeString(OUTPUT_DIR.resolve("diff.md"), report);
        System.out.println(report);
    }

    // -------------------------------------------------------------------------
    // Pipeline invocation (never propagates — records and returns null on failure)
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface PipelineCall {
        MapConfig run() throws IOException;
    }

    private MapConfig safeCall(String label, PipelineCall call, Map<String, String> exceptions) {
        try {
            return call.run();
        } catch (RuntimeException | IOException e) {
            exceptions.put(label, e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Diff report
    // -------------------------------------------------------------------------

    static record PipelineStats(
            int roadCount,
            int nodeCount,
            int intersectionCount,
            Map<String, Integer> intersectionTypes,
            int ringRoadCount,
            int inRoadCount,
            int outRoadCount,
            List<String> validatorErrors) {}

    private PipelineStats statsFor(MapConfig cfg) {
        if (cfg == null) {
            return new PipelineStats(0, 0, 0, new TreeMap<>(), 0, 0, 0, List.of());
        }
        List<MapConfig.RoadConfig> roads = cfg.getRoads() == null ? List.of() : cfg.getRoads();
        List<MapConfig.NodeConfig> nodes = cfg.getNodes() == null ? List.of() : cfg.getNodes();
        List<MapConfig.IntersectionConfig> ints =
                cfg.getIntersections() == null ? List.of() : cfg.getIntersections();

        Map<String, Integer> byType = new TreeMap<>();
        for (MapConfig.IntersectionConfig i : ints) {
            byType.merge(i.getType() == null ? "UNKNOWN" : i.getType(), 1, Integer::sum);
        }

        int ringRoads = 0;
        int inRoads = 0;
        int outRoads = 0;
        for (MapConfig.RoadConfig r : roads) {
            String id = r.getId() == null ? "" : r.getId();
            if (id.matches(".*r_ring_.*")) {
                ringRoads++;
            }
            if (id.endsWith("_in")) {
                inRoads++;
            } else if (id.endsWith("_out")) {
                outRoads++;
            }
        }

        List<String> errors;
        try {
            errors = validator.validate(cfg);
        } catch (RuntimeException e) {
            errors = List.of("validator threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return new PipelineStats(
                roads.size(), nodes.size(), ints.size(), byType, ringRoads, inRoads, outRoads, errors);
    }

    private String buildDiffReport(
            int fixtureBytes,
            MapConfig freeForm,
            MapConfig components,
            Map<String, String> exceptions) {
        PipelineStats a = statsFor(freeForm);
        PipelineStats b = statsFor(components);

        StringBuilder sb = new StringBuilder();
        sb.append("# Vision Comparison — ").append(Instant.now()).append('\n').append('\n');
        sb.append("Fixture: ").append(FIXTURE).append(" (").append(fixtureBytes).append(" bytes)\n\n");

        sb.append("| Metric                    | free-form (Phase 20) | components (Phase 21) | Match |\n");
        sb.append("|---------------------------|----------------------|------------------------|-------|\n");
        row(sb, "road count",            Integer.toString(a.roadCount),         Integer.toString(b.roadCount));
        row(sb, "node count",            Integer.toString(a.nodeCount),         Integer.toString(b.nodeCount));
        row(sb, "intersection count",    Integer.toString(a.intersectionCount), Integer.toString(b.intersectionCount));
        row(sb, "intersection types",    fmtTypes(a.intersectionTypes),         fmtTypes(b.intersectionTypes));
        row(sb, "ring roads (r_ring_*)", Integer.toString(a.ringRoadCount),     Integer.toString(b.ringRoadCount));
        row(sb, "_in/_out road pairs",   a.inRoadCount + "/" + a.outRoadCount,  b.inRoadCount + "/" + b.outRoadCount);
        row(sb, "validator errors",      fmtErrors(a.validatorErrors),          fmtErrors(b.validatorErrors));
        row(sb, "validator-clean",       Boolean.toString(a.validatorErrors.isEmpty() && freeForm != null),
                                         Boolean.toString(b.validatorErrors.isEmpty() && components != null));
        row(sb, "exception (if any)",    exceptions.getOrDefault("free-form", "—"),
                                         exceptions.getOrDefault("components", "—"));

        if (!exceptions.isEmpty()) {
            sb.append("\n## Exceptions\n\n");
            for (Map.Entry<String, String> e : exceptions.entrySet()) {
                sb.append("- **").append(e.getKey()).append("**: ").append(e.getValue()).append('\n');
            }
        }

        return sb.toString();
    }

    private static void row(StringBuilder sb, String metric, String left, String right) {
        String match = left.equals(right) ? "✓" : "✗";
        sb.append("| ").append(pad(metric, 25))
          .append(" | ").append(pad(left, 20))
          .append(" | ").append(pad(right, 22))
          .append(" | ").append(match).append("     |\n");
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) {
            return s;
        }
        StringBuilder out = new StringBuilder(w);
        out.append(s);
        while (out.length() < w) {
            out.append(' ');
        }
        return out.toString();
    }

    private static String fmtTypes(Map<String, Integer> types) {
        if (types.isEmpty()) {
            return "{}";
        }
        List<String> parts = new ArrayList<>();
        types.forEach((k, v) -> parts.add(k + ":" + v));
        return String.join(",", parts);
    }

    private static String fmtErrors(List<String> errors) {
        if (errors.isEmpty()) {
            return "[]";
        }
        return "[" + errors.size() + "]";
    }
}
