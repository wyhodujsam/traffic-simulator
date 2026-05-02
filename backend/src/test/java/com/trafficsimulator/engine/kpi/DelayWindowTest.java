package com.trafficsimulator.engine.kpi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelayWindowTest {

    private DelayWindow window;

    @BeforeEach
    void setUp() {
        window = new DelayWindow();
    }

    @Test
    void emptyReturnsZero() {
        assertThat(window.meanDelay(100L)).isZero();
    }

    @Test
    void recordsAndRetrieves() {
        window.recordDespawn(100L, 5.0);
        window.recordDespawn(200L, 10.0);
        assertThat(window.meanDelay(300L)).isEqualTo(7.5);
    }

    @Test
    void evictsOlderThanWindow() {
        window.recordDespawn(0L, 5.0);
        window.recordDespawn(1300L, 10.0);
        // currentTick=1300, cutoff = 1300-1200 = 100. Sample at 0 < 100 → evicted.
        assertThat(window.meanDelay(1300L)).isEqualTo(10.0);
    }

    @Test
    void resetClearsWindow() {
        window.recordDespawn(50L, 5.0);
        window.reset();
        assertThat(window.meanDelay(100L)).isZero();
    }

    @Test
    void sizeReflectsEvictionToo() {
        window.recordDespawn(0L, 1.0);
        window.recordDespawn(50L, 2.0);
        window.recordDespawn(1300L, 3.0);
        // cutoff = 100 — both 0 and 50 evicted
        assertThat(window.size(1300L)).isEqualTo(1);
    }
}
