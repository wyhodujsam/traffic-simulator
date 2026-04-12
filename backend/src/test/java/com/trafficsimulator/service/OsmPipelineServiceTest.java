package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Tests for OsmPipelineService.convertOsmToMapConfig() — the core OSM-to-MapConfig converter.
 * RestClient is not needed for these tests as they test the converter directly.
 */
class OsmPipelineServiceTest {

    private OsmPipelineService service;
    private final BboxRequest DEFAULT_BBOX = new BboxRequest(51.0, 17.0, 51.01, 17.01);

    @BeforeEach
    void setUp() {
        service = new OsmPipelineService(null, new ObjectMapper());
    }

    /** Build minimal Overpass JSON with given elements array content */
    private String overpassJson(String elements) {
        return "{\"elements\":[" + elements + "]}";
    }

    private String osmNode(long id, double lat, double lon, String... tagPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"node\",\"id\":").append(id)
                .append(",\"lat\":").append(lat)
                .append(",\"lon\":").append(lon);
        if (tagPairs.length > 0) {
            sb.append(",\"tags\":{");
            for (int i = 0; i < tagPairs.length; i += 2) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(tagPairs[i]).append("\":\"").append(tagPairs[i + 1]).append("\"");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private String osmWay(long id, long[] nodeIds, String... tagPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"way\",\"id\":").append(id)
                .append(",\"nodes\":[");
        for (int i = 0; i < nodeIds.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(nodeIds[i]);
        }
        sb.append("]");
        if (tagPairs.length > 0) {
            sb.append(",\"tags\":{");
            for (int i = 0; i < tagPairs.length; i += 2) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(tagPairs[i]).append("\":\"").append(tagPairs[i + 1]).append("\"");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    // Test 1: simple 2-node, 1-way -> 1 RoadConfig (bidirectional = 2 roads actually, but test fwd)
    @Test
    void convertOsmToMapConfig_simpleWay_producesRoadWithCorrectNodeIds() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        assertThat(config.getRoads()).isNotEmpty();
        MapConfig.RoadConfig fwd = config.getRoads().stream()
                .filter(r -> r.getId().equals("osm-1001-fwd"))
                .findFirst()
                .orElseThrow();
        assertThat(fwd.getFromNodeId()).isEqualTo("osm-101");
        assertThat(fwd.getToNodeId()).isEqualTo("osm-102");
    }

    // Test 2: lanes=3 tag -> laneCount=3
    @Test
    void convertOsmToMapConfig_explicitLanesTag_usesTagValue() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "primary", "lanes", "3")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        MapConfig.RoadConfig road = config.getRoads().stream()
                .filter(r -> r.getId().equals("osm-1001-fwd"))
                .findFirst().orElseThrow();
        assertThat(road.getLaneCount()).isEqualTo(3);
    }

    // Test 3: lanes=8 -> clamped to 4
    @Test
    void convertOsmToMapConfig_lanesTagExceedsMax_clampsToFour() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "motorway", "lanes", "8")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        MapConfig.RoadConfig road = config.getRoads().stream()
                .filter(r -> r.getId().equals("osm-1001-fwd"))
                .findFirst().orElseThrow();
        assertThat(road.getLaneCount()).isEqualTo(4);
    }

    // Test 4: no lanes tag, highway=primary -> default 2 lanes
    @Test
    void convertOsmToMapConfig_noLanesTag_usesHighwayDefault() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "primary")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        MapConfig.RoadConfig road = config.getRoads().stream()
                .filter(r -> r.getId().equals("osm-1001-fwd"))
                .findFirst().orElseThrow();
        assertThat(road.getLaneCount()).isEqualTo(2);
    }

    // Test 4b: no lanes tag, highway=residential -> default 1 lane
    @Test
    void convertOsmToMapConfig_noLanesTag_residentialUsesDefault1() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        MapConfig.RoadConfig road = config.getRoads().stream()
                .filter(r -> r.getId().equals("osm-1001-fwd"))
                .findFirst().orElseThrow();
        assertThat(road.getLaneCount()).isEqualTo(1);
    }

    // Test 5: node tagged highway=traffic_signals at shared node -> SIGNAL IntersectionConfig with signalPhases
    @Test
    void convertOsmToMapConfig_trafficSignalNode_producesSIGNALIntersection() {
        // Node 102 is shared between 2 ways AND tagged as traffic_signals
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005, "highway", "traffic_signals") + "," +
                osmNode(103, 51.007, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential") + "," +
                osmWay(1002, new long[]{102, 103}, "highway", "residential")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        assertThat(config.getIntersections()).isNotEmpty();
        MapConfig.IntersectionConfig ixtn = config.getIntersections().stream()
                .filter(i -> i.getNodeId().equals("osm-102"))
                .findFirst().orElseThrow();
        assertThat(ixtn.getType()).isEqualTo("SIGNAL");
        assertThat(ixtn.getSignalPhases()).isNotEmpty();
    }

    // Test 6: way tagged junction=roundabout -> ROUNDABOUT IntersectionConfig
    @Test
    void convertOsmToMapConfig_roundaboutWay_producesROUNDABOUTIntersection() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.006) + "," +
                osmNode(103, 51.007, 17.005) + "," +
                osmNode(104, 51.006, 17.004) + "," +
                osmWay(1001, new long[]{101, 102, 103, 104, 101},
                        "highway", "residential", "junction", "roundabout")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        assertThat(config.getIntersections()).isNotEmpty();
        boolean hasRoundabout = config.getIntersections().stream()
                .anyMatch(i -> "ROUNDABOUT".equals(i.getType()));
        assertThat(hasRoundabout).isTrue();
    }

    // Test 7: shared node (refCount >= 2) with no signal tag -> PRIORITY IntersectionConfig
    @Test
    void convertOsmToMapConfig_sharedNodeNoSignal_producesPRIORITYIntersection() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmNode(103, 51.007, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential") + "," +
                osmWay(1002, new long[]{102, 103}, "highway", "residential")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        assertThat(config.getIntersections()).isNotEmpty();
        MapConfig.IntersectionConfig ixtn = config.getIntersections().stream()
                .filter(i -> i.getNodeId().equals("osm-102"))
                .findFirst().orElseThrow();
        assertThat(ixtn.getType()).isEqualTo("PRIORITY");
    }

    // Test 8: terminal nodes produce SpawnPointConfig and DespawnPointConfig entries
    @Test
    void convertOsmToMapConfig_terminalNodes_produceSpawnAndDespawnPoints() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        assertThat(config.getSpawnPoints()).isNotEmpty();
        assertThat(config.getDespawnPoints()).isNotEmpty();
    }

    // Test 9a: oneway=yes -> only fwd road generated
    @Test
    void convertOsmToMapConfig_onewayYes_onlyFwdRoad() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential", "oneway", "yes")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        long count = config.getRoads().stream()
                .filter(r -> r.getId().startsWith("osm-1001"))
                .count();
        assertThat(count).isEqualTo(1);
        assertThat(config.getRoads().stream()
                .anyMatch(r -> r.getId().equals("osm-1001-fwd"))).isTrue();
    }

    // Test 9b: bidirectional way -> 2 roads (fwd + rev)
    @Test
    void convertOsmToMapConfig_bidirectionalWay_producesBothRoads() {
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmNode(102, 51.006, 17.005) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential")
        );

        MapConfig config = service.convertOsmToMapConfig(json, DEFAULT_BBOX);

        assertThat(config.getRoads().stream().anyMatch(r -> r.getId().equals("osm-1001-fwd"))).isTrue();
        assertThat(config.getRoads().stream().anyMatch(r -> r.getId().equals("osm-1001-rev"))).isTrue();
    }

    // Test 10: way with fewer than 2 resolvable nodes is skipped (results in no-roads exception)
    @Test
    void convertOsmToMapConfig_wayWithMissingNodes_isSkipped() {
        // Way references node 999 which doesn't exist in elements -> way skipped -> no roads -> exception
        String json = overpassJson(
                osmNode(101, 51.005, 17.005) + "," +
                osmWay(1001, new long[]{101, 999}, "highway", "residential")
        );

        assertThatThrownBy(() -> service.convertOsmToMapConfig(json, DEFAULT_BBOX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads found");
    }

    // Test 11: empty elements array -> throws IllegalStateException
    @Test
    void convertOsmToMapConfig_emptyElements_throwsIllegalStateException() {
        String json = overpassJson("");

        assertThatThrownBy(() -> service.convertOsmToMapConfig(json, DEFAULT_BBOX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No roads found");
    }

    // Test 12: Haversine distance — 2 nodes ~111m apart at equator
    @Test
    void convertOsmToMapConfig_haversineDistance_twoNodesOneDegreeLat_approx111m() {
        // 1 degree of latitude ≈ 111,320 meters; 0.001 degrees ≈ 111.32 meters
        String json = overpassJson(
                osmNode(101, 0.0, 0.0) + "," +
                osmNode(102, 0.001, 0.0) + "," +
                osmWay(1001, new long[]{101, 102}, "highway", "residential", "oneway", "yes")
        );
        BboxRequest bbox = new BboxRequest(-1.0, -1.0, 1.0, 1.0);

        MapConfig config = service.convertOsmToMapConfig(json, bbox);

        MapConfig.RoadConfig road = config.getRoads().stream()
                .filter(r -> r.getId().equals("osm-1001-fwd"))
                .findFirst().orElseThrow();
        // 0.001 degree latitude ≈ 111.32 meters; allow ±5m tolerance
        assertThat(road.getLength()).isBetween(106.0, 117.0);
    }
}
