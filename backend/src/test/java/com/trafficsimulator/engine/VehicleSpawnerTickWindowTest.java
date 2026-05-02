package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Deque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase-25 Plan-01 (DET-01 prerequisite): proves VehicleSpawner rolling throughput window is keyed
 * on simulated tick number rather than wall-clock milliseconds. Two runs with the same tick stream
 * therefore produce byte-identical throughput observations.
 */
class VehicleSpawnerTickWindowTest {

    private VehicleSpawner spawner;

    @BeforeEach
    void setUp() {
        spawner = new VehicleSpawner();
    }

    @Test
    void tickWindow_evictsEntriesOlderThanSixtySecondsOfTicks() throws Exception {
        seedDeque(0L, 100L, 500L, 1200L, 1300L);
        // currentTick=1300, cutoff = 1300 - 60*20 = 100. Entries < 100 are evicted (only tick 0).
        // Remaining: 100, 500, 1200, 1300 = 4 entries.
        assertThat(spawner.getThroughput(1300)).isEqualTo(4);
    }

    @Test
    void tickWindow_emptyAfterReset() throws Exception {
        seedDeque(50L, 100L);
        spawner.reset();
        assertThat(spawner.getThroughput(1000)).isZero();
    }

    @Test
    void tickWindow_neverNegativeOnFreshSpawner() {
        assertThat(spawner.getThroughput(0)).isZero();
    }

    @Test
    void tickWindow_sameTickWindow_inclusive() throws Exception {
        seedDeque(100L, 200L);
        // cutoff = 1300 - 1200 = 100. Entry at 100 is NOT < 100, so it stays.
        assertThat(spawner.getThroughput(1300)).isEqualTo(2);
        // cutoff = 1301 - 1200 = 101. Entry at 100 IS < 101, so evicted.
        assertThat(spawner.getThroughput(1301)).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private void seedDeque(Long... ticks) throws Exception {
        Field f = VehicleSpawner.class.getDeclaredField("despawnTicks");
        f.setAccessible(true);
        Deque<Long> deque = (Deque<Long>) f.get(spawner);
        deque.clear();
        for (Long t : ticks) {
            deque.addLast(t);
        }
    }
}
