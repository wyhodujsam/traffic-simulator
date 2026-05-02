package com.trafficsimulator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.trafficsimulator.dto.ObstacleDto;
import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.dto.TrafficLightDto;
import com.trafficsimulator.dto.VehicleDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.engine.kpi.DelayWindow;
import com.trafficsimulator.engine.kpi.KpiAggregator;
import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import com.trafficsimulator.model.TrafficLightPhase;
import com.trafficsimulator.model.Vehicle;

class SnapshotBuilderTest {

    private SnapshotBuilder snapshotBuilder;
    private IVehicleSpawner vehicleSpawner;

    @BeforeEach
    void setUp() {
        snapshotBuilder = new SnapshotBuilder();
        vehicleSpawner = mock(IVehicleSpawner.class);
        when(vehicleSpawner.getThroughput(anyLong())).thenReturn(0);
    }

    @Test
    void testVehicleDtoContainsDomainFields() {
        // Road from (0,0) to (1000,0), length=1000, single lane
        Lane lane =
                Lane.builder()
                        .id("r1-lane0")
                        .laneIndex(0)
                        .length(1000)
                        .maxSpeed(33.3)
                        .active(true)
                        .build();
        Road road =
                Road.builder()
                        .id("r1")
                        .name("Test Road")
                        .length(1000)
                        .speedLimit(33.3)
                        .startX(0)
                        .startY(0)
                        .endX(1000)
                        .endY(0)
                        .lanes(List.of(lane))
                        .build();
        lane.setRoad(road);

        // Vehicle at 50% position (500m)
        Vehicle v =
                Vehicle.builder()
                        .id("v1")
                        .position(500)
                        .speed(10.0)
                        .lane(lane)
                        .laneChangeSourceIndex(-1)
                        .laneChangeProgress(1.0)
                        .build();
        lane.addVehicle(v);

        VehicleDto dto = snapshotBuilder.buildVehicleDto(v, road, 0);

        assertThat(dto.getId()).isEqualTo("v1");
        assertThat(dto.getRoadId()).isEqualTo("r1");
        assertThat(dto.getLaneId()).isEqualTo("r1-lane0");
        assertThat(dto.getLaneIndex()).isZero();
        assertThat(dto.getPosition()).isCloseTo(500.0, within(0.01));
        assertThat(dto.getSpeed()).isCloseTo(10.0, within(0.01));
        assertThat(dto.getLaneChangeSourceIndex()).isEqualTo(-1);
        assertThat(dto.getLaneChangeProgress()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void testObstacleDtoContainsDomainFields() {
        // Diagonal road from (0,0) to (300,400), length=500
        Lane lane =
                Lane.builder()
                        .id("r1-lane0")
                        .laneIndex(0)
                        .length(500)
                        .maxSpeed(33.3)
                        .active(true)
                        .build();
        Road road =
                Road.builder()
                        .id("r1")
                        .name("Diagonal Road")
                        .length(500)
                        .speedLimit(33.3)
                        .startX(0)
                        .startY(0)
                        .endX(300)
                        .endY(400)
                        .lanes(List.of(lane))
                        .build();
        lane.setRoad(road);

        // Obstacle at 250m (50% position)
        Obstacle obs =
                Obstacle.builder()
                        .id("obs1")
                        .laneId("r1-lane0")
                        .position(250)
                        .length(3.0)
                        .createdAtTick(1)
                        .build();
        lane.addObstacle(obs);

        ObstacleDto dto = snapshotBuilder.buildObstacleDto(obs, road, 0);

        assertThat(dto.getId()).isEqualTo("obs1");
        assertThat(dto.getRoadId()).isEqualTo("r1");
        assertThat(dto.getLaneId()).isEqualTo("r1-lane0");
        assertThat(dto.getLaneIndex()).isZero();
        assertThat(dto.getPosition()).isCloseTo(250.0, within(0.01));
    }

    @Test
    void testStatsCalculation() {
        // Road with 3 vehicles at known speeds
        Lane lane =
                Lane.builder()
                        .id("r1-lane0")
                        .laneIndex(0)
                        .length(1000)
                        .maxSpeed(33.3)
                        .active(true)
                        .build();
        Road road =
                Road.builder()
                        .id("r1")
                        .name("Test Road")
                        .length(1000)
                        .speedLimit(33.3)
                        .startX(0)
                        .startY(0)
                        .endX(1000)
                        .endY(0)
                        .lanes(List.of(lane))
                        .build();
        lane.setRoad(road);

        // 3 vehicles: speeds 10, 20, 30
        for (int i = 0; i < 3; i++) {
            Vehicle v =
                    Vehicle.builder()
                            .id("v" + i)
                            .position(100 * (i + 1))
                            .speed(10.0 * (i + 1))
                            .lane(lane)
                            .laneChangeSourceIndex(-1)
                            .laneChangeProgress(1.0)
                            .build();
            lane.addVehicle(v);
        }

        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r1", road);
        RoadNetwork network =
                RoadNetwork.builder()
                        .id("test")
                        .roads(roads)
                        .intersections(new LinkedHashMap<>())
                        .build();

        SimulationStateDto state =
                snapshotBuilder.buildSnapshot(network, 1L, "RUNNING", vehicleSpawner, "test", null);

        assertThat(state.getStats().getVehicleCount()).isEqualTo(3);
        assertThat(state.getStats().getAvgSpeed()).isCloseTo(20.0, within(0.01)); // (10+20+30)/3
        // density = 3 vehicles / (1000m / 1000) = 3.0 vehicles/km
        assertThat(state.getStats().getDensity()).isCloseTo(3.0, within(0.01));
        assertThat(state.getVehicles()).hasSize(3);
    }

    @Test
    void testEmptyNetwork() {
        SimulationStateDto state =
                snapshotBuilder.buildSnapshot(null, 0L, "STOPPED", vehicleSpawner, null, null);

        assertThat(state.getVehicles()).isEmpty();
        assertThat(state.getObstacles()).isEmpty();
        assertThat(state.getTrafficLights()).isEmpty();
        assertThat(state.getStats().getVehicleCount()).isZero();
        assertThat(state.getStats().getAvgSpeed()).isEqualTo(0.0);
        assertThat(state.getStats().getDensity()).isEqualTo(0.0);
    }

    @Test
    void testTrafficLightDtos() {
        // Create a road ending at (500, 300)
        Lane lane =
                Lane.builder()
                        .id("r1-lane0")
                        .laneIndex(0)
                        .length(500)
                        .maxSpeed(33.3)
                        .active(true)
                        .build();
        Road road =
                Road.builder()
                        .id("r1")
                        .name("Inbound Road")
                        .length(500)
                        .speedLimit(33.3)
                        .startX(0)
                        .startY(0)
                        .endX(500)
                        .endY(300)
                        .lanes(List.of(lane))
                        .build();
        lane.setRoad(road);

        // Traffic light with GREEN phase for r1
        TrafficLightPhase greenPhase =
                TrafficLightPhase.builder()
                        .greenRoadIds(Set.of("r1"))
                        .durationMs(30000)
                        .type(TrafficLightPhase.PhaseType.GREEN)
                        .build();
        TrafficLight tl =
                TrafficLight.builder()
                        .intersectionId("ix1")
                        .phases(List.of(greenPhase))
                        .currentPhaseIndex(0)
                        .phaseElapsedMs(0)
                        .build();

        Intersection intersection =
                Intersection.builder()
                        .id("ix1")
                        .type(IntersectionType.SIGNAL)
                        .connectedRoadIds(new ArrayList<>(List.of("r1")))
                        .inboundRoadIds(new ArrayList<>(List.of("r1")))
                        .outboundRoadIds(new ArrayList<>())
                        .trafficLight(tl)
                        .build();

        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r1", road);
        Map<String, Intersection> intersections = new LinkedHashMap<>();
        intersections.put("ix1", intersection);

        RoadNetwork network =
                RoadNetwork.builder().id("test").roads(roads).intersections(intersections).build();

        SimulationStateDto state =
                snapshotBuilder.buildSnapshot(network, 1L, "RUNNING", vehicleSpawner, "test", null);

        assertThat(state.getTrafficLights()).hasSize(1);
        TrafficLightDto tlDto = state.getTrafficLights().get(0);
        assertThat(tlDto.getIntersectionId()).isEqualTo("ix1");
        assertThat(tlDto.getRoadId()).isEqualTo("r1");
        assertThat(tlDto.getState()).isEqualTo("GREEN");
        assertThat(tlDto.getX()).isCloseTo(500.0, within(0.01));
        assertThat(tlDto.getY()).isCloseTo(300.0, within(0.01));
        assertThat(tlDto.getAngle()).isCloseTo(Math.atan2(300, 500), within(0.001));
    }

    // --- Phase 25 KPI sub-sampling tests (KPI-05, KPI-07) ---

    private static RoadNetwork tinyNetwork() {
        Lane lane =
                Lane.builder()
                        .id("r1-lane0")
                        .laneIndex(0)
                        .length(100)
                        .maxSpeed(20.0)
                        .active(true)
                        .build();
        Road road =
                Road.builder()
                        .id("r1")
                        .name("R1")
                        .length(100)
                        .speedLimit(20.0)
                        .startX(0)
                        .startY(0)
                        .endX(100)
                        .endY(0)
                        .lanes(List.of(lane))
                        .build();
        lane.setRoad(road);
        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r1", road);
        return RoadNetwork.builder()
                .id("test")
                .roads(roads)
                .intersections(new LinkedHashMap<>())
                .build();
    }

    @Test
    void subSampling_recomputesEvery5thTick_KPI05() {
        DelayWindow dw = new DelayWindow();
        KpiAggregator real = new KpiAggregator(dw);
        KpiAggregator spyAgg = spy(real);
        ReflectionTestUtils.setField(snapshotBuilder, "kpiAggregator", spyAgg);

        RoadNetwork net = tinyNetwork();

        // Tick 5 (multiple of 5) — should compute.
        snapshotBuilder.buildSnapshot(net, 5L, "RUNNING", vehicleSpawner, "test", null);
        // Ticks 6..9 — should NOT compute (cached).
        for (long t = 6L; t <= 9L; t++) {
            snapshotBuilder.buildSnapshot(net, t, "RUNNING", vehicleSpawner, "test", null);
        }
        verify(spyAgg, times(1)).computeSegmentKpis(any(), anyLong());
        verify(spyAgg, times(1)).computeIntersectionKpis(any(), anyLong());
    }

    @Test
    void cacheClearedOnClearCache_KPI07() {
        DelayWindow dw = new DelayWindow();
        KpiAggregator real = new KpiAggregator(dw);
        KpiAggregator spyAgg = spy(real);
        ReflectionTestUtils.setField(snapshotBuilder, "kpiAggregator", spyAgg);

        RoadNetwork net = tinyNetwork();

        // Prime the cache at tick 5 (multiple of 5).
        snapshotBuilder.buildSnapshot(net, 5L, "RUNNING", vehicleSpawner, "test", null);
        verify(spyAgg, times(1)).computeSegmentKpis(any(), anyLong());

        // Clear cache (simulates LOAD_MAP / LOAD_CONFIG).
        snapshotBuilder.clearCache();

        // Next buildSnapshot at a multiple of 5 (tick 10) re-computes (count rises to 2).
        snapshotBuilder.buildSnapshot(net, 10L, "RUNNING", vehicleSpawner, "test", null);
        verify(spyAgg, times(2)).computeSegmentKpis(any(), anyLong());

        // After clearCache, calling at a non-multiple-of-5 (tick 6) returns empty (cache empty,
        // and tick % 5 != 0 means no recompute either).
        snapshotBuilder.clearCache();
        SimulationStateDto stateAt6 =
                snapshotBuilder.buildSnapshot(net, 6L, "RUNNING", vehicleSpawner, "test", null);
        assertThat(stateAt6.getStats().getSegmentKpis())
                .as("KPI-07: after clearCache + non-multiple-of-5 tick, segmentKpis is empty")
                .isEmpty();
        assertThat(stateAt6.getStats().getIntersectionKpis())
                .as("KPI-07: after clearCache + non-multiple-of-5 tick, intersectionKpis is empty")
                .isEmpty();
    }
}
