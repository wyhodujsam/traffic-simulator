package com.trafficsimulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Locks in the contract of promoted Phase 18 helpers now living in {@link OsmConversionUtils}.
 * Each test corresponds to a bullet from Plan 23-01's {@code <behavior>} block so Phase 23
 * cannot drift from Phase 18 without breaking these.
 */
class OsmConversionUtilsTest {

    // -------------------------------------------------------------------------
    // lonToX / latToY projection
    // -------------------------------------------------------------------------

    @Test
    void lonToX_atWestBoundary_returnsCanvasPadding() {
        double x = OsmConversionUtils.lonToX(10.0, 10.0, 20.0);
        assertThat(x).isEqualTo(OsmConversionUtils.CANVAS_PADDING);
    }

    @Test
    void lonToX_atEastBoundary_returnsCanvasWidthMinusPadding() {
        double x = OsmConversionUtils.lonToX(20.0, 10.0, 20.0);
        assertThat(x).isEqualTo(OsmConversionUtils.CANVAS_W - OsmConversionUtils.CANVAS_PADDING);
    }

    @Test
    void lonToX_atMidpoint_projectsToCanvasCentre() {
        double x = OsmConversionUtils.lonToX(15.0, 10.0, 20.0);
        assertThat(x).isEqualTo(800.0);
    }

    @Test
    void latToY_atNorthBoundary_returnsCanvasPadding() {
        double y = OsmConversionUtils.latToY(50.1, 50.0, 50.1);
        assertThat(y).isEqualTo(OsmConversionUtils.CANVAS_PADDING);
    }

    @Test
    void latToY_atSouthBoundary_returnsCanvasHeightMinusPadding() {
        double y = OsmConversionUtils.latToY(50.0, 50.0, 50.1);
        assertThat(y).isEqualTo(OsmConversionUtils.CANVAS_H - OsmConversionUtils.CANVAS_PADDING);
    }

    // -------------------------------------------------------------------------
    // haversineMeters
    // -------------------------------------------------------------------------

    @Test
    void haversineMeters_identicalPoints_returnsZero() {
        double d = OsmConversionUtils.haversineMeters(51.0, 17.0, 51.0, 17.0);
        assertThat(d).isZero();
    }

    @Test
    void haversineMeters_oneDegreeLatDelta_returnsAbout111Km() {
        double d = OsmConversionUtils.haversineMeters(0.0, 0.0, 1.0, 0.0);
        assertThat(d).isCloseTo(111_320.0, org.assertj.core.data.Offset.offset(500.0));
    }

    // -------------------------------------------------------------------------
    // computeWayLength (sum-of-segments)
    // -------------------------------------------------------------------------

    @Test
    void computeWayLength_emptyList_returnsZero() {
        double total = OsmConversionUtils.computeWayLength(List.of());
        assertThat(total).isZero();
    }

    @Test
    void computeWayLength_twoPointsOneDegreeApart_returnsAbout111Km() {
        List<double[]> path = List.of(new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
        double total = OsmConversionUtils.computeWayLength(path);
        assertThat(total).isCloseTo(111_320.0, org.assertj.core.data.Offset.offset(500.0));
    }

    @Test
    void computeWayLength_threePointsInLine_sumsSegments() {
        List<double[]> path =
                List.of(
                        new double[] {0.0, 0.0},
                        new double[] {1.0, 0.0},
                        new double[] {2.0, 0.0});
        double total = OsmConversionUtils.computeWayLength(path);
        // Each 1° segment is ~111.32 km; sum of two segments.
        assertThat(total).isCloseTo(222_640.0, org.assertj.core.data.Offset.offset(1_000.0));
    }

    // -------------------------------------------------------------------------
    // speedLimitForHighway
    // -------------------------------------------------------------------------

    @Test
    void speedLimitForHighway_motorway_returns36Point1() {
        assertThat(OsmConversionUtils.speedLimitForHighway("motorway")).isEqualTo(36.1);
    }

    @Test
    void speedLimitForHighway_residential_returns8Point3() {
        assertThat(OsmConversionUtils.speedLimitForHighway("residential")).isEqualTo(8.3);
    }

    @Test
    void speedLimitForHighway_unknownValue_returnsResidentialDefault() {
        assertThat(OsmConversionUtils.speedLimitForHighway("unknown_type")).isEqualTo(8.3);
    }

    @Test
    void speedLimitForHighway_livingStreet_returns2Point8() {
        assertThat(OsmConversionUtils.speedLimitForHighway("living_street")).isEqualTo(2.8);
    }

    // -------------------------------------------------------------------------
    // laneCountForWay
    // -------------------------------------------------------------------------

    @Test
    void laneCountForWay_explicitLanesTag_wins() {
        int n = OsmConversionUtils.laneCountForWay(Map.of("lanes", "3"), "residential");
        assertThat(n).isEqualTo(3);
    }

    @Test
    void laneCountForWay_clampsLanesToMaxFour() {
        int n = OsmConversionUtils.laneCountForWay(Map.of("lanes", "6"), "motorway");
        assertThat(n).isEqualTo(4);
    }

    @Test
    void laneCountForWay_nonNumericLanesTag_fallsThroughToHighwayDefault() {
        int n = OsmConversionUtils.laneCountForWay(Map.of("lanes", "abc"), "motorway");
        assertThat(n).isEqualTo(2);
    }

    @Test
    void laneCountForWay_noLanesTag_usesResidentialDefaultOne() {
        int n = OsmConversionUtils.laneCountForWay(Map.of(), "residential");
        assertThat(n).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // buildRoadConfig (fully-formed node ids)
    // -------------------------------------------------------------------------

    @Test
    void buildRoadConfig_setsAllFieldsFromArgs() {
        MapConfig.RoadConfig r =
                OsmConversionUtils.buildRoadConfig(
                        "osm-42-fwd", "osm-1", "osm-2", 100.0, 13.9, 2, 8.0);
        assertThat(r.getId()).isEqualTo("osm-42-fwd");
        assertThat(r.getFromNodeId()).isEqualTo("osm-1");
        assertThat(r.getToNodeId()).isEqualTo("osm-2");
        assertThat(r.getLength()).isEqualTo(100.0);
        assertThat(r.getSpeedLimit()).isEqualTo(13.9);
        assertThat(r.getLaneCount()).isEqualTo(2);
        assertThat(r.getLateralOffset()).isEqualTo(8.0);
    }

    @Test
    void buildRoadConfig_acceptsGhPrefix() {
        MapConfig.RoadConfig r =
                OsmConversionUtils.buildRoadConfig(
                        "gh-99-fwd", "gh-10", "gh-20", 50.0, 8.3, 1, 0.0);
        assertThat(r.getFromNodeId()).isEqualTo("gh-10");
        assertThat(r.getToNodeId()).isEqualTo("gh-20");
    }

    // -------------------------------------------------------------------------
    // buildDefaultSignalPhases
    // -------------------------------------------------------------------------

    @Test
    void buildDefaultSignalPhases_twoInboundRoads_returnsTwoGreenPhases() {
        List<MapConfig.RoadConfig> roads =
                List.of(
                        inboundRoadTo("osm-42", "r1"),
                        inboundRoadTo("osm-42", "r2"));

        List<MapConfig.SignalPhaseConfig> phases =
                OsmConversionUtils.buildDefaultSignalPhases("osm-42", roads);

        assertThat(phases).hasSize(2);
        assertThat(phases.get(0).getGreenRoadIds()).containsExactly("r1");
        assertThat(phases.get(0).getDurationMs()).isEqualTo(30_000L);
        assertThat(phases.get(0).getType()).isEqualTo("GREEN");
        assertThat(phases.get(1).getGreenRoadIds()).containsExactly("r2");
        assertThat(phases.get(1).getDurationMs()).isEqualTo(30_000L);
        assertThat(phases.get(1).getType()).isEqualTo("GREEN");
    }

    @Test
    void buildDefaultSignalPhases_ghPrefix_sameStructureAsOsmPrefix() {
        List<MapConfig.RoadConfig> roads =
                List.of(
                        inboundRoadTo("gh-42", "r1"),
                        inboundRoadTo("gh-42", "r2"));

        List<MapConfig.SignalPhaseConfig> phases =
                OsmConversionUtils.buildDefaultSignalPhases("gh-42", roads);

        assertThat(phases).hasSize(2);
        // Road ids are unchanged — we only parameterise the node lookup, not road id contents.
        assertThat(phases.get(0).getGreenRoadIds()).containsExactly("r1");
        assertThat(phases.get(1).getGreenRoadIds()).containsExactly("r2");
    }

    // -------------------------------------------------------------------------
    // collectSpawnPoints / collectDespawnPoints
    // -------------------------------------------------------------------------

    @Test
    void collectSpawnPoints_addsOnePerLaneAtPositionZero() {
        MapConfig.RoadConfig road = new MapConfig.RoadConfig();
        road.setId("r1");
        road.setFromNodeId("osm-1");
        road.setToNodeId("osm-9");
        road.setLaneCount(3);
        road.setLength(100.0);

        List<MapConfig.SpawnPointConfig> sink = new ArrayList<>();
        OsmConversionUtils.collectSpawnPoints("osm-1", List.of(road), sink);

        assertThat(sink).hasSize(3);
        assertThat(sink.get(0).getPosition()).isZero();
        assertThat(sink.get(0).getRoadId()).isEqualTo("r1");
        assertThat(sink.get(0).getLaneIndex()).isZero();
        assertThat(sink.get(2).getLaneIndex()).isEqualTo(2);
    }

    @Test
    void collectDespawnPoints_addsOnePerLaneAtRoadLength() {
        MapConfig.RoadConfig road = new MapConfig.RoadConfig();
        road.setId("r1");
        road.setFromNodeId("osm-8");
        road.setToNodeId("osm-2");
        road.setLaneCount(2);
        road.setLength(250.0);

        List<MapConfig.DespawnPointConfig> sink = new ArrayList<>();
        OsmConversionUtils.collectDespawnPoints("osm-2", List.of(road), sink);

        assertThat(sink).hasSize(2);
        assertThat(sink.get(0).getPosition()).isEqualTo(250.0);
        assertThat(sink.get(0).getRoadId()).isEqualTo("r1");
        assertThat(sink.get(1).getLaneIndex()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // assembleMapConfig
    // -------------------------------------------------------------------------

    @Test
    void assembleMapConfig_buildsBboxIdFromCoords() {
        BboxRequest bbox = new BboxRequest(52.2, 21.0, 52.3, 21.1);
        MapConfig cfg =
                OsmConversionUtils.assembleMapConfig(
                        bbox,
                        List.of(nodeWithId("n1")),
                        List.of(roadWithId("r1")),
                        List.of(),
                        List.of(),
                        List.of());
        assertThat(cfg.getId()).isEqualTo("osm-bbox-52.2000-21.0000-52.3000-21.1000");
        assertThat(cfg.getName()).isEqualTo("OSM Import");
        assertThat(cfg.getDefaultSpawnRate()).isEqualTo(1.0);
    }

    @Test
    void assembleMapConfig_emptyIntersectionsList_storedAsNull() {
        BboxRequest bbox = new BboxRequest(52.2, 21.0, 52.3, 21.1);
        MapConfig cfg =
                OsmConversionUtils.assembleMapConfig(
                        bbox,
                        List.of(nodeWithId("n1")),
                        List.of(roadWithId("r1")),
                        List.of(),
                        List.of(),
                        List.of());
        assertThat(cfg.getIntersections()).isNull();
        assertThat(cfg.getSpawnPoints()).isNull();
        assertThat(cfg.getDespawnPoints()).isNull();
    }

    @Test
    void assembleMapConfig_nonEmptyLists_storedVerbatim() {
        BboxRequest bbox = new BboxRequest(52.2, 21.0, 52.3, 21.1);
        MapConfig.IntersectionConfig ix = new MapConfig.IntersectionConfig();
        ix.setNodeId("osm-1");
        ix.setType("PRIORITY");
        MapConfig cfg =
                OsmConversionUtils.assembleMapConfig(
                        bbox,
                        List.of(nodeWithId("n1")),
                        List.of(roadWithId("r1")),
                        List.of(ix),
                        List.of(),
                        List.of());
        assertThat(cfg.getIntersections()).hasSize(1);
        assertThat(cfg.getIntersections().get(0).getNodeId()).isEqualTo("osm-1");
    }

    // -------------------------------------------------------------------------
    // Utility class shape: private constructor, public static members only
    // -------------------------------------------------------------------------

    @Test
    void utilityClass_hasPrivateConstructor() throws Exception {
        var ctor = OsmConversionUtils.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static MapConfig.RoadConfig inboundRoadTo(String toNodeId, String roadId) {
        MapConfig.RoadConfig r = new MapConfig.RoadConfig();
        r.setId(roadId);
        r.setFromNodeId("osm-from-" + roadId);
        r.setToNodeId(toNodeId);
        r.setLaneCount(1);
        r.setLength(100.0);
        return r;
    }

    private static MapConfig.NodeConfig nodeWithId(String id) {
        MapConfig.NodeConfig n = new MapConfig.NodeConfig();
        n.setId(id);
        n.setType("ENTRY");
        n.setX(100.0);
        n.setY(100.0);
        return n;
    }

    private static MapConfig.RoadConfig roadWithId(String id) {
        MapConfig.RoadConfig r = new MapConfig.RoadConfig();
        r.setId(id);
        r.setFromNodeId("osm-1");
        r.setToNodeId("osm-2");
        r.setLength(100.0);
        r.setSpeedLimit(8.3);
        r.setLaneCount(1);
        return r;
    }
}
