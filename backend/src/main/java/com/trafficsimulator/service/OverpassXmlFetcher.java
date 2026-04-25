package com.trafficsimulator.service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.trafficsimulator.dto.BboxRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared helper for fetching Overpass API XML payloads. Used by both the Phase 23 GraphHopper
 * converter ({@link GraphHopperOsmService}) and the Phase 24 osm2streets converter
 * ({@link Osm2StreetsService}) so there is a single Overpass mirror-loop / query / error-handling
 * path to reason about.
 *
 * <p>Phase 18 ({@link OsmPipelineService}) fetches Overpass <b>JSON</b>, not XML, and is therefore
 * intentionally NOT refactored onto this helper — different output format, different parsing path.
 *
 * <p>All public methods accept a {@link BboxRequest} and return either the raw XML as UTF-8 bytes
 * (for streaming into a subprocess via stdin — osm2streets-cli pattern) or write it to a temp file
 * on disk (for parsers that only consume {@code File}-based inputs — GraphHopper's
 * {@code WaySegmentParser}).
 */
@Service
@Slf4j
public class OverpassXmlFetcher {

    private final RestClient overpassRestClient;
    private final List<String> overpassMirrors;

    public OverpassXmlFetcher(
            RestClient overpassRestClient,
            @Value("${osm.overpass.urls:https://overpass-api.de}") List<String> overpassMirrors) {
        this.overpassRestClient = overpassRestClient;
        this.overpassMirrors = overpassMirrors;
    }

    /**
     * Fetches the Overpass XML payload for the given bbox and returns it as UTF-8 bytes. Suitable
     * for piping directly to a subprocess's stdin (Phase 24 osm2streets-cli pattern).
     *
     * @throws RestClientException if every configured mirror fails
     */
    public byte[] fetchXmlBytes(BboxRequest bbox) {
        String query = buildOverpassXmlQuery(bbox);
        String encoded = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        log.info("Fetching OSM data (XML) for bbox: {} (mirrors={})", bbox, overpassMirrors);
        String xml = fetchFromMirrors(encoded);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Fetches the Overpass XML payload for the given bbox and writes it to a new file under
     * {@code tempDir}. Returns the path to the created file. Callers are responsible for deleting
     * the file when done.
     *
     * @throws IOException if the file cannot be written
     * @throws RestClientException if every configured mirror fails
     */
    public Path fetchXmlToTempFile(BboxRequest bbox, Path tempDir) throws IOException {
        String query = buildOverpassXmlQuery(bbox);
        String encoded = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        log.info("Fetching OSM data (XML) for bbox: {} (mirrors={})", bbox, overpassMirrors);
        String xml = fetchFromMirrors(encoded);

        Path osmFile = tempDir.resolve("bbox.osm");
        Files.writeString(osmFile, xml, StandardCharsets.UTF_8);
        return osmFile;
    }

    /**
     * Builds the Overpass XML query for a bbox. Package-private so unit tests can inspect the
     * produced query shape.
     */
    String buildOverpassXmlQuery(BboxRequest bbox) {
        return """
                [out:xml][timeout:25];
                (
                  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$"](%f,%f,%f,%f);
                );
                out body;
                >;
                out skel qt;\
                """
                .formatted(bbox.south(), bbox.west(), bbox.north(), bbox.east());
    }

    /**
     * Iterates over configured mirrors and returns the first successful response body. Throws a
     * {@link RestClientException} if none succeed. Package-private for reuse by tests that want to
     * assert the mirror-failover contract.
     */
    String fetchFromMirrors(String encodedBody) {
        RestClientException lastError = null;
        for (String baseUrl : overpassMirrors) {
            String url = baseUrl.replaceAll("/+$", "") + "/api/interpreter";
            try {
                log.info("Overpass attempt: {}", url);
                return overpassRestClient
                        .post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(encodedBody)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException e) {
                log.warn("Overpass mirror {} failed: {}", baseUrl, e.getMessage());
                lastError = e;
            }
        }
        throw new RestClientException(
                "All Overpass mirrors failed (" + overpassMirrors.size() + " tried)",
                lastError != null ? lastError : new IllegalStateException("no mirrors configured"));
    }
}
