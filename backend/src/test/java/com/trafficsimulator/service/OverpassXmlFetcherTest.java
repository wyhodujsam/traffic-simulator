package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.trafficsimulator.dto.BboxRequest;

/**
 * Phase 24.1 unit-level regression guard for the Overpass query string shape.
 *
 * <p>The bug being pinned: the original {@code buildOverpassXmlQuery} emitted
 * <pre>
 *   (way[...](...););
 *   out body;
 *   &gt;;
 *   out skel qt;
 * </pre>
 * which produces XML with {@code <way>} elements BEFORE {@code <node>} elements — invalid input
 * for both osm2streets (Phase 24, returns 0 roads) and GraphHopper (Phase 23, "Could not parse
 * OSM file"). The fix moves the recursion INSIDE the union and uses a single {@code out body qt;}
 * directive, which produces standard nodes-before-ways XML.
 *
 * <p>These tests assert the corrected query SHAPE only — they do NOT touch HTTP. Plan 02 covers
 * the end-to-end behavior (real Overpass-XML response → real Osm2StreetsService /
 * GraphHopperOsmService → MapConfig) AND ALSO captures the request body sent to the mock
 * Overpass server to assert this same query-shape contract at the integration layer.
 */
class OverpassXmlFetcherTest {

    // OverpassXmlFetcher requires a RestClient + a mirrors list. Neither is touched by
    // buildOverpassXmlQuery, so a throwaway RestClient + empty mirrors list is fine.
    private final OverpassXmlFetcher fetcher =
            new OverpassXmlFetcher(RestClient.create(), List.of());

    // Real BboxRequest record — never mocked (mockito-anti-patterns Anti-Pattern 2).
    private final BboxRequest bbox = new BboxRequest(52.431, 20.65, 52.438, 20.662);

    @Test
    void buildOverpassXmlQuery_recursesInsideUnion() {
        String query = fetcher.buildOverpassXmlQuery(bbox);

        // Recursion `>;` MUST appear before the union-closing `);`.
        // The new form is:    way[...](...);\n  >;\n);\nout body qt;
        // The old (broken) form was: way[...](...);\n);\nout body;\n>;\nout skel qt;
        assertThat(query)
                .as("recursion `>;` must live INSIDE the union, immediately before `);`")
                .containsPattern(Pattern.compile(">;\\s*\\n\\s*\\);"));

        // The trailing directive must be `out body qt;` — full body so osm2streets sees
        // node tags (e.g. highway=traffic_signals). Was `out skel qt;` before the fix.
        assertThat(query)
                .as("trailing directive must be `out body qt;`")
                .contains("out body qt;");
    }

    @Test
    void buildOverpassXmlQuery_doesNotEmitSeparateOutBody() {
        String query = fetcher.buildOverpassXmlQuery(bbox);

        // The old form had `out body;` followed on the next line by `>;`. The new form has
        // NO standalone `out body;` directive — there is exactly one `out` directive total.
        assertThat(query)
                .as("no separate `out body;` followed by `>;` (the buggy two-pass form)")
                .doesNotContainPattern(Pattern.compile("out\\s+body;\\s*\\n\\s*>;"));

        // Also: only ONE `out ...;` directive total in the query.
        long outDirectiveCount =
                Pattern.compile("(?m)^\\s*out\\s+\\w").matcher(query).results().count();
        assertThat(outDirectiveCount)
                .as("exactly one `out ...` directive (single-pass union recursion form)")
                .isEqualTo(1L);
    }

    @Test
    void buildOverpassXmlQuery_interpolatesBboxAsSWNE() {
        String query = fetcher.buildOverpassXmlQuery(bbox);

        // Co-validation: the bbox MUST be interpolated as south,west,north,east. Guards against
        // accidental coordinate-order swap during the edit. Floats render with %f → 6 decimals.
        assertThat(query).contains("52.431000,20.650000,52.438000,20.662000");
    }
}
