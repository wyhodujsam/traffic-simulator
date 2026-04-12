package com.trafficsimulator.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
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

/**
 * REST endpoint for fetching OSM road data and converting it to a {@link MapConfig}.
 */
@RestController
@RequestMapping("/api/osm")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class OsmController {

    private final OsmPipelineService osmPipelineService;

    /**
     * Fetches roads from the Overpass API for the given bounding box and returns a MapConfig JSON.
     *
     * @param bbox bounding box in WGS84 coordinates
     * @return 200 MapConfig on success, 422 if area has no roads, 503 on Overpass API failure
     */
    @PostMapping("/fetch-roads")
    public ResponseEntity<?> fetchRoads(@RequestBody BboxRequest bbox) {
        try {
            MapConfig config = osmPipelineService.fetchAndConvert(bbox);
            log.info("OSM fetch succeeded: {} roads for bbox {}",
                    config.getRoads() != null ? config.getRoads().size() : 0, bbox);
            return ResponseEntity.ok(config);
        } catch (IllegalStateException e) {
            log.warn("OSM fetch returned no data: {}", e.getMessage());
            return ResponseEntity.status(422)
                    .body(Map.of("error", e.getMessage()));
        } catch (RestClientException e) {
            log.error("OSM fetch Overpass API error: {}", e.getMessage());
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Overpass API unavailable. Please try again later."));
        } catch (Exception e) {
            log.error("OSM fetch unexpected error: {}", e.getMessage());
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Overpass API unavailable. Please try again later."));
        }
    }
}
