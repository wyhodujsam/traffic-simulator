package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Phase 24.1 default-enabled integration smoke test that closes the test-coverage gap which
 * allowed the Overpass XML element-ordering bug to ship through master.
 *
 * <p>This test runs by default in {@code mvn test} (NO {@code @Disabled}, NO
 * {@code @EnabledIfSystemProperty} gate; only an {@code @EnabledOnOs(LINUX)} gate because the
 * osm2streets-cli binary in {@code backend/bin/} is Linux-x64 only). It binds
 * {@link MockRestServiceServer} to the application's {@code overpassRestClient} bean so the
 * Overpass HTTP call never leaves the JVM, and it answers with a frozen Overpass XML fixture
 * checked into {@code src/test/resources/osm/overpass-real-modlin.xml}. The fixture has
 * nodes-before-ways ordering — i.e. the shape that the FIXED query produces.
 *
 * <p>Both services are exercised end-to-end:
 * <ul>
 *   <li>{@link Osm2StreetsService} → real osm2streets-cli subprocess → {@link StreetNetworkMapper}
 *   <li>{@link GraphHopperOsmService} → real {@code WaySegmentParser} → {@code convertToMapConfig}
 * </ul>
 * Both must return a {@link MapConfig} with at least 1 road that passes {@link MapValidator}.
 *
 * <p><b>Issue #1 closure (checker revision 1):</b> the test ALSO captures the URL-encoded
 * Overpass POST body that each service sends and asserts on the produced query SHAPE:
 * <ul>
 *   <li>POSITIVE: contains {@code >;);out body qt;} (whitespace-insensitive — recursion-in-union
 *       form delivered by Plan 24.1-01).
 *   <li>NEGATIVE: does NOT contain {@code out body;} followed by {@code >;} (the buggy
 *       two-pass form).
 * </ul>
 * This makes Plan 02 a true regression net for the original bug: reverting Plan 01's fix to
 * {@code out body;\n>;\nout body qt;} would FAIL the
 * {@link #bothServicesEmitRecursionInsideUnionAndSingleOutBodyQt_regressionGuard()} test — even
 * though the canned XML alone cannot detect that revert (the fixture is the consumer-side input,
 * not the query-string output).
 *
 * <p><b>Wiring note:</b> {@link MockRestServiceServer#bindTo(RestClient.Builder)} is the only
 * Spring 6.1 overload that accepts a {@code RestClient}-flavored argument; there is no
 * {@code bindTo(RestClient)} overload. We therefore expose an inner {@link TestConfig} that builds
 * the {@code overpassRestClient} bean from a {@link RestClient.Builder} we cache, then bind the
 * mock server to that cached builder. The production {@code OsmClientConfig} produces a
 * {@code RestClient} from a generic builder + {@code SimpleClientHttpRequestFactory}; our
 * {@code @Primary} test bean overrides that one for the duration of this @SpringBootTest.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "osm.overpass.urls=http://test-overpass.local",
            // Allow our @TestConfig overpassRestClient bean to override the production
            // OsmClientConfig.overpassRestClient bean — Spring Boot disables this by default
            // since 2.1, so we opt in explicitly for this @SpringBootTest only.
            "spring.main.allow-bean-definition-overriding=true",
            // Maven runs the surefire JVM with cwd=backend/, but the production-default
            // osm2streets.binary-path is "backend/bin/osm2streets-cli-linux-x64" (relative to
            // the repo root, used when the JAR is run from the project root). When tests run,
            // the cwd is one level deeper, so we override to the cwd-relative path here.
            "osm2streets.binary-path=bin/osm2streets-cli-linux-x64"
        })
@EnabledOnOs(OS.LINUX)
class OsmPipelineSmokeIT {

    /**
     * Modlin / Nowy Dwór Mazowiecki bbox — the same area documented in 24.1-CONTEXT.md as the
     * real-world reproducer for the bug.
     */
    private static final BboxRequest BBOX =
            new BboxRequest(52.431, 20.65, 52.438, 20.662);

    /**
     * Positive query-shape contract: recursion {@code >;} lives INSIDE the union (immediately
     * before the closing {@code );}) and the trailing directive is {@code out body qt;}.
     * Whitespace-insensitive so formatting changes in
     * {@link OverpassXmlFetcher#buildOverpassXmlQuery} do not break this.
     */
    private static final Pattern POSITIVE_QUERY_SHAPE =
            Pattern.compile(">;\\s*\\);\\s*out\\s+body\\s+qt;");

    /**
     * Negative query-shape contract: the buggy two-pass form {@code out body;\n>;} MUST NOT
     * appear. This is the EXACT regression-net pattern: reverting Plan 01's fix would
     * re-introduce this substring and trip this assertion.
     */
    private static final Pattern NEGATIVE_QUERY_SHAPE =
            Pattern.compile("out\\s+body;\\s*\\n\\s*>;");

    /**
     * Replaces the production {@code overpassRestClient} bean with one built from a builder we
     * bind {@link MockRestServiceServer} to BEFORE building the {@link RestClient}. Critical
     * ordering: {@code MockRestServiceServer.bindTo(builder).build()} mutates the builder's
     * request factory; only after that do we call {@code builder.build()} to produce the actual
     * {@link RestClient} that {@link OverpassXmlFetcher} will inject. If we built the RestClient
     * first and bound later, the in-flight client would still hold the original
     * {@code JdkClientHttpRequestFactory} and the mock would never see the calls.
     *
     * <p>Both the {@link MockRestServiceServer} and the resulting {@link RestClient} are exposed
     * as Spring beans. The test autowires the mock server to set up expectations; Spring
     * autowires the {@code @Primary} {@link RestClient} into {@link OverpassXmlFetcher},
     * overriding the production {@code OsmClientConfig.overpassRestClient} bean (override
     * permitted via {@code spring.main.allow-bean-definition-overriding=true} in
     * {@link TestPropertySource} above).
     */
    @TestConfiguration
    static class TestConfig {

        /**
         * Declare our own {@link RestClient.Builder} as a singleton-scoped bean — Spring Boot's
         * auto-configured {@code RestClient.Builder} is prototype-scoped, so each injection
         * point would receive a different instance and the {@code MockRestServiceServer} bind
         * (which mutates the builder's request factory) would not be visible to the
         * {@link RestClient} subsequently built from a different builder copy.
         */
        @Bean
        @Primary
        RestClient.Builder overpassTestRestClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer overpassMockRestServiceServer(
                RestClient.Builder overpassTestRestClientBuilder) {
            // bindTo() mutates the builder to install a MockClientHttpRequestFactory; the
            // RestClient subsequently built from THIS SAME builder will route through the mock.
            return MockRestServiceServer.bindTo(overpassTestRestClientBuilder).build();
        }

        @Bean
        @Primary
        RestClient overpassRestClient(
                RestClient.Builder overpassTestRestClientBuilder,
                MockRestServiceServer overpassMockRestServiceServer) {
            // Listing overpassMockRestServiceServer as a parameter forces it to be created first,
            // ensuring bindTo() has installed the mock factory on overpassTestRestClientBuilder
            // before we call build() here.
            return overpassTestRestClientBuilder.build();
        }
    }

    @Autowired private MockRestServiceServer mockServer;
    @Autowired private Osm2StreetsService osm2StreetsService;
    @Autowired private GraphHopperOsmService graphHopperOsmService;
    @Autowired private MapValidator mapValidator;

    private String cannedOverpassXml;
    private final List<String> capturedQueries = new ArrayList<>();

    /**
     * Captures the URL-decoded Overpass query string from each POST. The body shape is
     * {@code data=%5Bout%3Axml%5D...} — the prefix {@code data=} is stripped and the rest
     * URL-decoded. {@code MockClientHttpRequest.getBodyAsString()} does NOT throw IOException,
     * so the lambda is checked-exception-free.
     */
    private final RequestMatcher captureBody = request -> {
        MockClientHttpRequest mock = (MockClientHttpRequest) request;
        String rawBody = mock.getBodyAsString();
        String formValue =
                rawBody.startsWith("data=") ? rawBody.substring("data=".length()) : rawBody;
        String decoded = URLDecoder.decode(formValue, StandardCharsets.UTF_8);
        capturedQueries.add(decoded);
    };

    @BeforeEach
    void setUp() throws Exception {
        // mockServer is a Spring-managed singleton bean (one per ApplicationContext). Reset
        // expectations + recorded requests between tests so each @Test starts clean.
        mockServer.reset();
        capturedQueries.clear();

        Path fixture =
                Paths.get(
                        Objects.requireNonNull(
                                        getClass()
                                                .getClassLoader()
                                                .getResource("osm/overpass-real-modlin.xml"),
                                        "fixture not found: osm/overpass-real-modlin.xml")
                                .toURI());
        cannedOverpassXml = Files.readString(fixture, StandardCharsets.UTF_8);
    }

    /**
     * Asserts the captured Overpass query body has the corrected shape. Called from each
     * service's @Test method AND from the dedicated regression-guard test.
     */
    private void assertOverpassQueryShape(String capturedBody, String contextLabel) {
        assertThat(capturedBody)
                .as(
                        "[%s] captured Overpass query must use recursion-in-union form"
                                + " (`>;);out body qt;`)",
                        contextLabel)
                .containsPattern(POSITIVE_QUERY_SHAPE);
        assertThat(capturedBody)
                .as(
                        "[%s] captured Overpass query MUST NOT use the buggy two-pass form"
                                + " (`out body;\\n>;`)",
                        contextLabel)
                .doesNotContainPattern(NEGATIVE_QUERY_SHAPE);
        assertThat(capturedBody)
                .as("[%s] captured query must include the fixed bbox in S,W,N,E order", contextLabel)
                .contains("52.431", "20.65", "52.438", "20.662");
    }

    @Test
    void osm2StreetsService_fetchAndConvert_realBinary_returnsAtLeastOneRoad() {
        mockServer
                .expect(requestTo(CoreMatchers.endsWith("/api/interpreter")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureBody)
                .andRespond(withSuccess(cannedOverpassXml, MediaType.APPLICATION_XML));

        MapConfig cfg = osm2StreetsService.fetchAndConvert(BBOX);

        assertThat(cfg).as("osm2streets must return a MapConfig").isNotNull();
        assertThat(cfg.getRoads())
                .as("osm2streets must return at least 1 road for the canned Modlin XML")
                .isNotNull()
                .isNotEmpty();
        assertThat(mapValidator.validate(cfg))
                .as("osm2streets output must pass MapValidator")
                .isEmpty();

        // ID-prefix sanity check (StreetNetworkMapper uses "o2s-").
        assertThat(cfg.getRoads())
                .allSatisfy(r -> assertThat(r.getId()).startsWith("o2s-"));

        // Issue #1: the query the service ACTUALLY sent must be the corrected shape.
        assertThat(capturedQueries).as("exactly one Overpass POST captured").hasSize(1);
        assertOverpassQueryShape(capturedQueries.get(0), "Osm2StreetsService");

        mockServer.verify();
    }

    @Test
    void
            graphHopperOsmService_fetchAndConvert_realParser_returnsAtLeastOneRoadAndOneSignalIntersection() {
        mockServer
                .expect(requestTo(CoreMatchers.endsWith("/api/interpreter")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureBody)
                .andRespond(withSuccess(cannedOverpassXml, MediaType.APPLICATION_XML));

        MapConfig cfg = graphHopperOsmService.fetchAndConvert(BBOX);

        assertThat(cfg).as("GraphHopper must return a MapConfig").isNotNull();
        assertThat(cfg.getRoads())
                .as("GraphHopper must return at least 1 road for the canned Modlin XML")
                .isNotNull()
                .isNotEmpty();

        assertThat(cfg.getIntersections())
                .as("GraphHopper must detect the traffic_signals node as a SIGNAL intersection")
                .anySatisfy(ic -> assertThat(ic.getType()).isEqualTo("SIGNAL"));

        assertThat(mapValidator.validate(cfg))
                .as("GraphHopper output must pass MapValidator")
                .isEmpty();

        // ID-prefix sanity check (GraphHopperOsmService uses "gh-").
        assertThat(cfg.getRoads())
                .allSatisfy(r -> assertThat(r.getId()).startsWith("gh-"));

        // Issue #1: the query the service ACTUALLY sent must be the corrected shape.
        assertThat(capturedQueries).as("exactly one Overpass POST captured").hasSize(1);
        assertOverpassQueryShape(capturedQueries.get(0), "GraphHopperOsmService");

        mockServer.verify();
    }

    /**
     * Phase 24.1 Issue #1 regression net: explicitly couples the produced Overpass query shape
     * (Plan 01's contract) to the integration test (Plan 02's harness). Reverting Plan 01's fix
     * to {@code out body;\n>;\nout body qt;} causes this test to FAIL — making Plan 02 a true
     * backstop for the original bug class even though the canned XML alone cannot detect such a
     * revert.
     *
     * <p>Both services are driven through one fixture-served Overpass response so we get
     * coverage of both the {@code fetchXmlBytes} (Osm2StreetsService) and
     * {@code fetchXmlToTempFile} (GraphHopperOsmService) code paths in a single test.
     */
    @Test
    void bothServicesEmitRecursionInsideUnionAndSingleOutBodyQt_regressionGuard() {
        // Expect TWO POSTs (one per service) — both captured.
        mockServer
                .expect(requestTo(CoreMatchers.endsWith("/api/interpreter")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureBody)
                .andRespond(withSuccess(cannedOverpassXml, MediaType.APPLICATION_XML));
        mockServer
                .expect(requestTo(CoreMatchers.endsWith("/api/interpreter")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureBody)
                .andRespond(withSuccess(cannedOverpassXml, MediaType.APPLICATION_XML));

        // Drive both services — discard the MapConfig outputs; this test only asserts on the
        // captured query shapes.
        osm2StreetsService.fetchAndConvert(BBOX);
        graphHopperOsmService.fetchAndConvert(BBOX);

        assertThat(capturedQueries)
                .as("two captured Overpass POSTs (one per service)")
                .hasSize(2);

        // Both queries must satisfy the corrected-shape contract.
        assertOverpassQueryShape(capturedQueries.get(0), "Osm2StreetsService (regressionGuard)");
        assertOverpassQueryShape(capturedQueries.get(1), "GraphHopperOsmService (regressionGuard)");

        // Bonus: both queries should be IDENTICAL (same OverpassXmlFetcher.buildOverpassXmlQuery
        // logic, same bbox). If they differ, something has been refactored between the two
        // services that we did not expect.
        assertThat(capturedQueries.get(0))
                .as("both services must produce the same query string for the same bbox")
                .isEqualTo(capturedQueries.get(1));

        mockServer.verify();
    }
}
