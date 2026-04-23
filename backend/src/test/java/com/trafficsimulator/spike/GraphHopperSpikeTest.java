package com.trafficsimulator.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.graphhopper.reader.osm.WaySegmentParser;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.util.PointList;

import com.trafficsimulator.TrafficSimulatorApplication;

/**
 * Phase 23 Wave-0 discovery spike. Non-gating: tests only fail on unexpected exceptions; PASS/FAIL
 * for the research assumptions is written as bullets to 23-SPIKE.md.
 *
 * <p>This entire package is throwaway — Plan 07 deletes it after the phase closes.
 */
class GraphHopperSpikeTest {

    /**
     * Phase directory path. Appends go to {@code 23-SPIKE.md} in this directory. Resolved against
     * the working directory Maven runs from (repo root OR backend/ — both handled below).
     */
    private static final String PHASE_DIR_NAME =
            "23-graphhopper-based-osm-parser-swap-custom-overpass-converter-for-graphhopper-"
                    + "osmreader-waysegmentparser-to-get-cleaner-intersection-splitting-coexists-"
                    + "with-existing-osmpipelineservice-via-a-api-osm-fetch-roads-gh-endpoint-for-"
                    + "a-b-comparison";

    @Test
    void nodeTagsExposedByEdgeHandler_a1() throws IOException {
        // 1. Build a tiny OSM XML fixture: 3 nodes, middle one tagged highway=traffic_signals.
        String osmXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<osm version=\"0.6\" generator=\"spike\">\n"
                    + "  <node id=\"1\" lat=\"52.2200\" lon=\"21.0000\"/>\n"
                    + "  <node id=\"2\" lat=\"52.2210\" lon=\"21.0010\">\n"
                    + "    <tag k=\"highway\" v=\"traffic_signals\"/>\n"
                    + "  </node>\n"
                    + "  <node id=\"3\" lat=\"52.2220\" lon=\"21.0020\"/>\n"
                    + "  <way id=\"100\">\n"
                    + "    <nd ref=\"1\"/><nd ref=\"2\"/><nd ref=\"3\"/>\n"
                    + "    <tag k=\"highway\" v=\"residential\"/>\n"
                    + "  </way>\n"
                    + "</osm>\n";

        Path tmpDir = Files.createTempDirectory("gh-spike-a1-");
        Path osmFile = tmpDir.resolve("fixture.osm");
        Files.writeString(osmFile, osmXml, StandardCharsets.UTF_8);

        // 2. In-memory graph.
        Directory dir = new RAMDirectory();
        BaseGraph graph = new BaseGraph.Builder(0).setDir(dir).create();

        // 3. Accumulators.
        AtomicInteger edgeCount = new AtomicInteger(0);
        List<List<Map<String, Object>>> capturedNodeTagsPerEdge = new ArrayList<>();
        StringBuilder evidence = new StringBuilder();

        // 4. Configure parser — single-threaded for deterministic ordering.
        WaySegmentParser parser =
                new WaySegmentParser.Builder(graph.getNodeAccess(), dir)
                        .setWayFilter(way -> way.hasTag("highway"))
                        .setEdgeHandler(
                                (from, to, pointList, way, nodeTags) -> {
                                    edgeCount.incrementAndGet();
                                    capturedNodeTagsPerEdge.add(new ArrayList<>(nodeTags));
                                    evidence.append("  edge ")
                                            .append(from)
                                            .append("->")
                                            .append(to)
                                            .append(" points=")
                                            .append(pointList.size())
                                            .append(" wayTags={highway=")
                                            .append(way.getTag("highway"))
                                            .append("} nodeTags=")
                                            .append(nodeTags)
                                            .append('\n');
                                })
                        .setWorkerThreads(1)
                        .build();

        // 5. Parse — MUST NOT throw (only fail() path in this test).
        parser.readOSM(osmFile.toFile());

        // 6. Evaluate A1 PASS criterion: any captured nodeTags map contains highway=traffic_signals.
        boolean signalTagFound = false;
        for (List<Map<String, Object>> perEdge : capturedNodeTagsPerEdge) {
            for (Map<String, Object> nt : perEdge) {
                Object hwy = nt.get("highway");
                if (hwy != null && "traffic_signals".equals(hwy.toString())) {
                    signalTagFound = true;
                    break;
                }
            }
            if (signalTagFound) {
                break;
            }
        }

        String details =
                "edgeCount="
                        + edgeCount.get()
                        + "\n"
                        + "capturedNodeTags (one entry per parsed segment):\n"
                        + evidence
                        + "signalTagFound="
                        + signalTagFound;

        appendSpikeResult("A1", signalTagFound, details);

        // Sanity: parser did not explode, at least one edge was produced.
        assertThat(edgeCount.get()).as("WaySegmentParser produced at least one edge").isGreaterThanOrEqualTo(1);

        graph.close();
    }

    @Test
    void failingServiceBeanDoesNotAbortContext_a7() {
        // No @SpringBootTest on the class — the programmatic bootstrap below is the only Spring
        // lifecycle trigger, so the @Test body executes regardless of whether the context crashes.
        SpringApplicationBuilder builder =
                new SpringApplicationBuilder(
                                TrafficSimulatorApplication.class, FailingServiceBeanProbe.class)
                        // Keep the probe run quick and quiet. No web server, no banner.
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                        .properties("spring.main.lazy-initialization=false");

        String outcome;
        String exceptionClass = "";
        String exceptionMessage = "";
        try (ConfigurableApplicationContext ctx = builder.run()) {
            outcome = "RAN_TO_COMPLETION";
        } catch (org.springframework.beans.factory.BeanCreationException ex) {
            outcome = "CONTEXT_ABORTED_BeanCreationException";
            exceptionClass = ex.getClass().getName();
            exceptionMessage = String.valueOf(ex.getMessage());
        } catch (org.springframework.context.ApplicationContextException ex) {
            outcome = "CONTEXT_ABORTED_ApplicationContextException";
            exceptionClass = ex.getClass().getName();
            exceptionMessage = String.valueOf(ex.getMessage());
        } catch (Throwable t) {
            outcome = "CONTEXT_ABORTED_OTHER";
            exceptionClass = t.getClass().getName();
            exceptionMessage = String.valueOf(t.getMessage());
        }

        boolean contextAborted = outcome.startsWith("CONTEXT_ABORTED_");

        String details =
                "outcome="
                        + outcome
                        + "\n"
                        + "exceptionClass="
                        + exceptionClass
                        + "\n"
                        + "exceptionMessage="
                        + exceptionMessage;

        // A7 claim in RESEARCH.md says "context survives failing bean". If contextAborted=true
        // then the research claim is WRONG — which we record as A7=FAIL (claim failed to hold).
        // If contextAborted=false then the claim held — A7=PASS. See 23-SPIKE.md prose for the
        // inverted naming explanation.
        boolean a7ClaimHeld = !contextAborted;
        appendSpikeResult("A7", a7ClaimHeld, details);

        // Sanity: we must reach this point with a deterministic outcome string.
        assertThat(outcome).isNotEmpty();
    }

    /**
     * Tiny Spring {@code @Configuration} that registers a single {@code @Bean} whose factory
     * method throws. Used to probe whether Spring aborts the context when a bean fails to
     * construct.
     */
    @Configuration
    static class FailingServiceBeanProbe {
        @Bean
        DummyBean dummyBean() {
            return new DummyBean();
        }
    }

    /** Throws from the constructor — drives the A7 scenario. */
    static class DummyBean {
        DummyBean() {
            throw new RuntimeException("simulated init failure (A7 spike probe)");
        }
    }

    /**
     * Appends a bullet to {@code 23-SPIKE.md} in the phase directory. Tries two relative paths
     * (repo root and backend/) so the test works regardless of Maven's CWD.
     */
    private static void appendSpikeResult(String section, boolean pass, String details) {
        String line =
                "- **"
                        + section
                        + ":** "
                        + (pass ? "PASS" : "FAIL")
                        + "\n```\n"
                        + details
                        + "\n```\n";

        Path repoRoot = Path.of(".planning", "phases", PHASE_DIR_NAME, "23-SPIKE.md");
        Path fromBackend = Path.of("..", ".planning", "phases", PHASE_DIR_NAME, "23-SPIKE.md");

        Path target;
        if (Files.exists(repoRoot.getParent())) {
            target = repoRoot;
        } else if (Files.exists(fromBackend.getParent())) {
            target = fromBackend;
        } else {
            // Last resort: try repo-root path anyway, will CREATE the file.
            target = repoRoot;
        }

        try {
            // Ensure parent dir exists (it should — the phase dir is committed).
            Files.createDirectories(target.getParent());
            Files.writeString(
                    target,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Surface via stderr — test does not fail on logging issues.
            System.err.println(
                    "[spike] failed to append to 23-SPIKE.md at "
                            + target.toAbsolutePath()
                            + ": "
                            + e);
        }
    }
}
