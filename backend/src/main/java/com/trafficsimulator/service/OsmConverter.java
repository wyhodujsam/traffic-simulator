package com.trafficsimulator.service;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Shared contract for OSM-to-{@link MapConfig} converters.
 *
 * <p>Phase 18's {@link OsmPipelineService} (Overpass-based) is the first implementation. Phase 23
 * will add a second ({@code GraphHopperOsmService}), and Phase 24 is expected to add a third
 * (osm2streets). The abstraction is deliberately thin: a single contract method for conversion
 * plus a default human-readable label used by A/B comparison logging.
 *
 * <p>Implementations MUST produce output that passes {@code MapValidator}. Failure modes are
 * reported via the documented exception taxonomy so controllers can map them to a consistent HTTP
 * response shape regardless of which converter backs the request.
 *
 * <p><b>Availability semantics:</b> the {@link #isAvailable()} default hook lets a converter
 * declare itself usable at request time. It exists to pair with the Wave 0 spike finding (23-SPIKE
 * A7 = FAIL: failing {@code @Service} beans abort the Spring context). Future implementations
 * wired via {@code @Lazy} + {@code ObjectProvider} can override this to return {@code false} when
 * their underlying engine (e.g. GraphHopper's {@code WaySegmentParser}) cannot be initialised,
 * letting the controller degrade gracefully instead of returning 500. Phase 18's
 * {@link OsmPipelineService} is always available — the default {@code true} fits.
 */
public interface OsmConverter {

    /**
     * Fetches OSM data for the given bounding box and converts it to a simulation-ready
     * {@link MapConfig}.
     *
     * <p>Implementations MUST produce output that passes {@code MapValidator}.
     *
     * @param bbox bounding box in WGS84
     * @return populated {@link MapConfig} ready for loading into the simulation engine
     * @throws IllegalStateException if no usable roads are found in the area
     * @throws org.springframework.web.client.RestClientException if the upstream OSM source is
     *     unavailable
     */
    MapConfig fetchAndConvert(BboxRequest bbox);

    /**
     * Human-readable identifier for A/B comparison logging. Defaults to the implementation's
     * simple class name; override for stable, short labels like {@code "Overpass"},
     * {@code "GraphHopper"}, or {@code "osm2streets"} so logs read cleanly:
     * {@code "Overpass: 42 roads / 11 intersections; GraphHopper: 38 roads / 9 intersections"}.
     *
     * @return short label identifying this converter
     */
    default String converterName() {
        return getClass().getSimpleName();
    }

    /**
     * Indicates whether this converter is currently usable. Defaults to {@code true}.
     *
     * <p>Implementations with heavyweight external dependencies (e.g. GraphHopper's native
     * parser) can override to return {@code false} when their engine failed to initialise, so
     * controllers can return a 503 "backend unavailable" response instead of letting a
     * construction error propagate. This is the controller-side counterpart of the {@code @Lazy}
     * + {@code ObjectProvider} mitigation mandated by 23-SPIKE {@code ## A7}.
     *
     * @return {@code true} if this converter can handle {@link #fetchAndConvert} calls
     */
    default boolean isAvailable() {
        return true;
    }
}
