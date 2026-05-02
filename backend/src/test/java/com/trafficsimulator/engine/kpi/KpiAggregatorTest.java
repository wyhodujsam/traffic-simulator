package com.trafficsimulator.engine.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.dto.KpiDto;
import com.trafficsimulator.dto.SegmentKpiDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.Vehicle;

/**
 * Covers KPI-01 (throughput delegation), KPI-02 (mean delay reads DelayWindow), KPI-04 (worstLos
 * across segments), and a sanity check that segment KPI fields are populated for an empty road.
 */
class KpiAggregatorTest {

    @Test
    void throughput_delegatesToVehicleSpawner() {
        DelayWindow dw = new DelayWindow();
        KpiAggregator agg = new KpiAggregator(dw);
        IVehicleSpawner spawner = mock(IVehicleSpawner.class);
        when(spawner.getThroughput(100L)).thenReturn(30);
        KpiDto kpi = agg.computeNetworkKpi(emptyNetwork(), 100L, spawner);
        assertThat(kpi.getThroughputVehiclesPerMin()).isEqualTo(30.0);
    }

    @Test
    void meanDelay_readsDelayWindow() {
        DelayWindow dw = new DelayWindow();
        dw.recordDespawn(100L, 5.0);
        dw.recordDespawn(200L, 12.0);
        KpiAggregator agg = new KpiAggregator(dw);
        KpiDto kpi = agg.computeNetworkKpi(emptyNetwork(), 300L, mockSpawner());
        assertThat(kpi.getMeanDelaySeconds()).isEqualTo(8.5);
    }

    @Test
    void worstLos_isMaxAcrossSegments() {
        // Each road is 1000m long, single lane, speedLimit 22.2 m/s.
        // density (veh/km/lane) = (vehicleCount * 1000) / length / laneCount
        //   so vehicleCount=5  -> 5  veh/km/lane -> LOS A
        //      vehicleCount=14 -> 14 veh/km/lane -> LOS C
        //      vehicleCount=26 -> 26 veh/km/lane -> LOS E
        DelayWindow dw = new DelayWindow();
        KpiAggregator agg = new KpiAggregator(dw);

        Road roadA = mockRoad("rA", 1000.0, 22.2, 5);
        Road roadC = mockRoad("rC", 1000.0, 22.2, 14);
        Road roadE = mockRoad("rE", 1000.0, 22.2, 26);

        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("rA", roadA);
        roads.put("rC", roadC);
        roads.put("rE", roadE);
        RoadNetwork network = mock(RoadNetwork.class);
        when(network.getRoads()).thenReturn(roads);
        when(network.getIntersections()).thenReturn(new LinkedHashMap<>());

        IVehicleSpawner spawner = mockSpawner();

        KpiDto kpi = agg.computeNetworkKpi(network, 100L, spawner);
        assertThat(kpi.getWorstLos())
                .as("worstLos must be the max across segments (E > C > A)")
                .isEqualTo("E");
    }

    @Test
    void segmentKpi_populatedFields_emptyRoad() {
        DelayWindow dw = new DelayWindow();
        KpiAggregator agg = new KpiAggregator(dw);
        Road road = mock(Road.class);
        when(road.getId()).thenReturn("r1");
        when(road.getLength()).thenReturn(250.0);
        lenient().when(road.getSpeedLimit()).thenReturn(22.2);
        when(road.getLanes()).thenReturn(List.of());
        Map<String, Road> roads = new LinkedHashMap<>();
        roads.put("r1", road);
        RoadNetwork network = mock(RoadNetwork.class);
        when(network.getRoads()).thenReturn(roads);

        List<SegmentKpiDto> segs = agg.computeSegmentKpis(network, 100L);
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).getRoadId()).isEqualTo("r1");
        assertThat(segs.get(0).getLos()).isEqualTo("A"); // empty road → density 0 → LOS A
    }

    /**
     * Builds a Road mock with one lane containing exactly {@code vehicleCount} vehicles moving at
     * the speed limit (so {@link QueueAnalyzer} returns 0 and density alone drives LOS).
     */
    private Road mockRoad(String id, double length, double speedLimit, int vehicleCount) {
        Road road = mock(Road.class);
        when(road.getId()).thenReturn(id);
        when(road.getLength()).thenReturn(length);
        when(road.getSpeedLimit()).thenReturn(speedLimit);

        Lane lane = mock(Lane.class);
        List<Vehicle> vs = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            Vehicle v = mock(Vehicle.class);
            lenient().when(v.getSpeed()).thenReturn(speedLimit); // moving at speed limit -> not queued
            lenient().when(v.getPosition()).thenReturn((double) i);
            vs.add(v);
        }
        when(lane.getVehiclesView()).thenReturn(vs);
        when(road.getLanes()).thenReturn(List.of(lane));
        return road;
    }

    private RoadNetwork emptyNetwork() {
        RoadNetwork n = mock(RoadNetwork.class);
        when(n.getRoads()).thenReturn(new LinkedHashMap<>());
        lenient().when(n.getIntersections()).thenReturn(new LinkedHashMap<>());
        return n;
    }

    private IVehicleSpawner mockSpawner() {
        IVehicleSpawner s = mock(IVehicleSpawner.class);
        lenient().when(s.getThroughput(anyLong())).thenReturn(0);
        return s;
    }
}
