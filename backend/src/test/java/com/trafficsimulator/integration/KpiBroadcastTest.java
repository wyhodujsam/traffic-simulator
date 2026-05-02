package com.trafficsimulator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.trafficsimulator.dto.KpiDto;
import com.trafficsimulator.dto.SimulationStateDto;
import com.trafficsimulator.engine.IVehicleSpawner;
import com.trafficsimulator.scheduler.SnapshotBuilder;

/**
 * KPI-06: every {@code SimulationStateDto} broadcast on /topic/state after Start carries a
 * {@code stats.kpi != null} block.
 *
 * <p>Implementation: rather than spinning up a real STOMP broker test client, we drive the
 * production {@link SnapshotBuilder} the same way TickEmitter does and verify the kpi block is
 * always populated. The broadcast wire is exercised by the wider e2e suite (Plan 07 Task 3).
 *
 * <p>File naming: {@code *Test.java}.
 */
class KpiBroadcastTest extends Phase25IntegrationBase {

    @Autowired SnapshotBuilder snapshotBuilder;
    @Autowired IVehicleSpawner spawner;

    @Test
    void everyFrameHasKpiBlock_KPI06() throws Exception {
        loadScenario("ring-road");
        startAndRunFast(0L, 50L);

        // Sample 10 consecutive snapshots. Each must carry stats != null and stats.kpi != null.
        for (int i = 0; i < 10; i++) {
            SimulationStateDto state =
                    snapshotBuilder.buildSnapshot(
                            new SnapshotBuilder.SnapshotConfig(
                                    engine.getRoadNetwork(),
                                    engine.getTickCounter().get() + i,
                                    "RUNNING",
                                    spawner,
                                    engine.getRoadNetwork().getId(),
                                    null));
            assertThat(state.getStats())
                    .as("KPI-06: stats must be present on frame " + i)
                    .isNotNull();
            KpiDto kpi = state.getStats().getKpi();
            assertThat(kpi)
                    .as("KPI-06: kpi block must be present on frame " + i)
                    .isNotNull();
            assertThat(kpi.getThroughputVehiclesPerMin())
                    .as("KPI-06: throughputVehiclesPerMin >= 0")
                    .isGreaterThanOrEqualTo(0.0);
            assertThat(kpi.getWorstLos())
                    .as("KPI-06: worstLos must be one of A..F (got " + kpi.getWorstLos() + ")")
                    .isIn("A", "B", "C", "D", "E", "F");
        }
    }
}
