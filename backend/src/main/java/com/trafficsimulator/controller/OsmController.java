package com.trafficsimulator.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;
import com.trafficsimulator.service.GraphHopperOsmService;
import com.trafficsimulator.service.Osm2StreetsService;
import com.trafficsimulator.service.OsmPipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** REST endpoint for fetching OSM road data and converting it to a {@link MapConfig}. */
@RestController
@RequestMapping("/api/osm")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class OsmController {

    private static final String ERROR_KEY = "error";

    private final OsmPipelineService osmPipelineService;
    private final GraphHopperOsmService graphHopperOsmService;
    private final Osm2StreetsService osm2StreetsService;

    /**
     * Fetches roads from the Overpass API for the given bounding box and returns a MapConfig JSON.
     *
     * @param bbox bounding box in WGS84 coordinates
     * @return 200 MapConfig on success
     */
    @PostMapping("/fetch-roads")
    public ResponseEntity<MapConfig> fetchRoads(@RequestBody BboxRequest bbox) {
        MapConfig config = osmPipelineService.fetchAndConvert(bbox);
        log.info(
                "OSM fetch succeeded: {} roads for bbox {}",
                config.getRoads() != null ? config.getRoads().size() : 0,
                bbox);
        return ResponseEntity.ok(config);
    }

    /**
     * GraphHopper-based OSM conversion (Phase 23). Additive to {@link #fetchRoads}, coexists for
     * A/B comparison. Shares the exception taxonomy (422/503) with the Phase 18 endpoint via the
     * class-level exception handlers below.
     *
     * @param bbox bounding box in WGS84 coordinates
     * @return 200 MapConfig on success
     */
    @PostMapping("/fetch-roads-gh")
    public ResponseEntity<MapConfig> fetchRoadsGh(@RequestBody BboxRequest bbox) {
        MapConfig config = graphHopperOsmService.fetchAndConvert(bbox);
        log.info(
                "OSM (GraphHopper) fetch succeeded: {} roads for bbox {}",
                config.getRoads() != null ? config.getRoads().size() : 0,
                bbox);
        return ResponseEntity.ok(config);
    }

    /**
     * osm2streets-based OSM conversion (Phase 24). Additive to {@link #fetchRoads} and
     * {@link #fetchRoadsGh}, coexists for A/B/C comparison. Surfaces lane-level metadata via the
     * optional {@code RoadConfig.lanes} field introduced in Phase 24-02.
     *
     * <p>Exception taxonomy (see class-level handlers): 422 empty area, 503 osm2streets CLI /
     * Overpass fault, 504 osm2streets timeout.
     *
     * @param bbox bounding box in WGS84 coordinates
     * @return 200 MapConfig on success
     */
    @PostMapping("/fetch-roads-o2s")
    public ResponseEntity<MapConfig> fetchRoadsO2s(@RequestBody BboxRequest bbox) {
        MapConfig config = osm2StreetsService.fetchAndConvert(bbox);
        log.info(
                "OSM (osm2streets) fetch succeeded: {} roads for bbox {}",
                config.getRoads() != null ? config.getRoads().size() : 0,
                bbox);
        return ResponseEntity.ok(config);
    }

    /**
     * Malformed request body (non-JSON, missing required fields that trip Jackson's deserialiser,
     * empty body). Returns 400 instead of falling through to the generic 503 handler — the client
     * sent a bad request, the backend is not unavailable.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMalformedPayload(
            HttpMessageNotReadableException e) {
        log.warn("OSM fetch received malformed request body: {}", e.getMessage());
        return ResponseEntity.status(400)
                .body(Map.of(ERROR_KEY, "Malformed request body"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNoData(IllegalStateException e) {
        log.warn("OSM fetch returned no data: {}", e.getMessage());
        return ResponseEntity.status(422).body(Map.of(ERROR_KEY, e.getMessage()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> handleOverpassError(RestClientException e) {
        log.error("OSM fetch Overpass API error: {}", e.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of(ERROR_KEY, "Overpass API unavailable. Please try again later."));
    }

    /**
     * osm2streets subprocess fault (non-zero exit, IO pipe failure, invalid JSON, unavailable
     * binary) — maps to 503 with a generic message. Kept as an explicit handler rather than
     * extending {@link RestClientException} because the two failures have different operator
     * semantics in logs.
     */
    @ExceptionHandler(Osm2StreetsService.Osm2StreetsCliException.class)
    public ResponseEntity<Map<String, String>> handleO2sCli(
            Osm2StreetsService.Osm2StreetsCliException e) {
        log.error("osm2streets CLI error: {}", e.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of(ERROR_KEY, "osm2streets unavailable. Please try again later."));
    }

    /**
     * osm2streets subprocess hit the configured timeout — maps to 504 (Gateway Timeout) so clients
     * can distinguish "backend slow" from "backend broken" (503). The error body hints at the
     * smaller-bbox mitigation.
     */
    @ExceptionHandler(Osm2StreetsService.Osm2StreetsCliTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleO2sTimeout(
            Osm2StreetsService.Osm2StreetsCliTimeoutException e) {
        log.error("osm2streets timeout: {}", e.getMessage());
        return ResponseEntity.status(504)
                .body(Map.of(ERROR_KEY, "osm2streets timed out. Try a smaller bbox."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("OSM fetch unexpected error: {}", e.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of(ERROR_KEY, "Overpass API unavailable. Please try again later."));
    }
}
