package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.junit.jupiter.api.Test;

/**
 * Proves {@link VehicleSpawner#vary(double)} draws from the injected {@link RandomGenerator} and
 * therefore produces identical sequences across runs when seeded identically (D-03 contract).
 *
 * <p>Uses reflection to call the private {@code vary} helper; this is the only place we exercise
 * the IDM-noise sub-RNG in isolation. Production callers reach it via {@link
 * VehicleSpawner#tick(double, com.trafficsimulator.model.RoadNetwork, long)}.
 */
class VehicleSpawnerRngTest {

    private static final String ALGO = SimulationEngine.MASTER_ALGORITHM;

    @Test
    void vary_usesInjectedRng_sameSeed_sameSequence() throws Exception {
        VehicleSpawner a = new VehicleSpawner();
        VehicleSpawner b = new VehicleSpawner();
        a.setRng(RandomGeneratorFactory.of(ALGO).create(42L));
        b.setRng(RandomGeneratorFactory.of(ALGO).create(42L));
        assertThat(callVary(a, 5)).containsExactlyElementsOf(callVary(b, 5));
    }

    @Test
    void vary_differsAcrossSeeds() throws Exception {
        VehicleSpawner a = new VehicleSpawner();
        VehicleSpawner b = new VehicleSpawner();
        a.setRng(RandomGeneratorFactory.of(ALGO).create(1L));
        b.setRng(RandomGeneratorFactory.of(ALGO).create(2L));
        assertThat(callVary(a, 5)).isNotEqualTo(callVary(b, 5));
    }

    @Test
    void setRng_replacesPreviousGenerator() throws Exception {
        VehicleSpawner a = new VehicleSpawner();
        a.setRng(RandomGeneratorFactory.of(ALGO).create(1L));
        List<Double> first = callVary(a, 5);
        a.setRng(RandomGeneratorFactory.of(ALGO).create(1L)); // reset to same seed
        List<Double> second = callVary(a, 5);
        assertThat(second).containsExactlyElementsOf(first);
    }

    private List<Double> callVary(VehicleSpawner spawner, int n) throws Exception {
        Method m = VehicleSpawner.class.getDeclaredMethod("vary", double.class);
        m.setAccessible(true);
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add((Double) m.invoke(spawner, 10.0));
        }
        return out;
    }
}
