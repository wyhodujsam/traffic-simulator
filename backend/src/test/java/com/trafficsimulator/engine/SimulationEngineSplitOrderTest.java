package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers DET-05: sub-RNG split order is fixed (D-02 append-only contract). Adding a new
 * consumer in a future plan must append at the END of the spawn list, never insert in the
 * middle, otherwise existing seeded streams reshuffle and break byte-identity.
 *
 * <p>Pure-construction unit test — no Spring context. Asserts that with the same seed two
 * consecutive {@code resolveSeedAndStart} invocations produce identical first nextLong() draws
 * from each of the three sub-RNGs IN ORDER (spawnerRng → ixtnRoutingRng → idmNoiseRng).
 */
class SimulationEngineSplitOrderTest {

    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SimulationEngine(null, null, new VehicleSpawner(), new IntersectionManager(), null);
    }

    @Test
    void splitOrder_isFixed_acrossInvocationsWithSameSeed() {
        engine.resolveSeedAndStart(42L);
        long s1 = engine.getSpawnerRng().nextLong();
        long i1 = engine.getIxtnRoutingRng().nextLong();
        long n1 = engine.getIdmNoiseRng().nextLong();

        engine.resolveSeedAndStart(42L);
        long s2 = engine.getSpawnerRng().nextLong();
        long i2 = engine.getIxtnRoutingRng().nextLong();
        long n2 = engine.getIdmNoiseRng().nextLong();

        assertThat(s1).as("spawnerRng first draw stable across runs").isEqualTo(s2);
        assertThat(i1).as("ixtnRoutingRng first draw stable across runs").isEqualTo(i2);
        assertThat(n1).as("idmNoiseRng first draw stable across runs").isEqualTo(n2);
    }

    @Test
    void splitOrder_subRngsAreDistinct() {
        engine.resolveSeedAndStart(42L);
        long s = engine.getSpawnerRng().nextLong();
        long i = engine.getIxtnRoutingRng().nextLong();
        long n = engine.getIdmNoiseRng().nextLong();
        assertThat(s).as("spawner != routing first draw").isNotEqualTo(i);
        assertThat(i).as("routing != noise first draw").isNotEqualTo(n);
        assertThat(s).as("spawner != noise first draw").isNotEqualTo(n);
    }
}
