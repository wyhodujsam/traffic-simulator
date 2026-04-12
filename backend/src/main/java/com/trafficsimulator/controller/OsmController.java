package com.trafficsimulator.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("OSM fetch unexpected error: {}", e.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of(ERROR_KEY, "Overpass API unavailable. Please try again later."));
    }
}
