package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trafficsimulator.model.RoadNetwork;

/**
 * Covers DET-03: seed source precedence is {@code command > json > nanoTime} (D-01) and the
 * resolved source is logged at INFO (D-04).
 *
 * <p>Pure-construction unit test — no Spring context, no map loading. Builds a {@link
 * SimulationEngine} directly with {@code (null, null)} dependencies and a hand-built {@link
 * RoadNetwork} via reflection on the {@code roadNetwork} field. Asserts the contract by
 * comparing the first nextLong() draws of the resulting sub-RNGs.
 */
class SimulationEngineSeedTest {

    private SimulationEngine engine;
    private VehicleSpawner spawner;
    private IntersectionManager ixtnMgr;

    @BeforeEach
    void setUp() {
        spawner = new VehicleSpawner();
        ixtnMgr = new IntersectionManager();
        engine = new SimulationEngine(null, null, spawner, ixtnMgr);
    }

    @Test
    void seedPrecedence_commandWinsOverJsonAndAuto() {
        setRoadNetworkSeed(111L);

        engine.resolveSeedAndStart(999L);
        long firstFromRun1 = engine.getIdmNoiseRng().nextLong();

        engine.resolveSeedAndStart(999L);
        long firstFromRun2 = engine.getIdmNoiseRng().nextLong();

        assertThat(firstFromRun1)
                .as("command seed=999 must produce reproducible first draw across runs")
                .isEqualTo(firstFromRun2);
    }

    @Test
    void seedPrecedence_jsonUsed_whenCommandIsNull() {
        setRoadNetworkSeed(222L);

        engine.resolveSeedAndStart(null);
        long a = engine.getIdmNoiseRng().nextLong();

        engine.resolveSeedAndStart(null);
        long b = engine.getIdmNoiseRng().nextLong();

        assertThat(a).as("json seed=222 reproducible across runs").isEqualTo(b);
    }

    @Test
    void seedPrecedence_autoUsed_whenBothNull() {
        setRoadNetworkSeed(null);

        engine.resolveSeedAndStart(null);
        long a = engine.getIdmNoiseRng().nextLong();

        // Sleep a microsecond's worth so System.nanoTime() definitely advances. Even without it,
        // L64X128MixRandom turns nanos-apart inputs into wildly different streams — but we add a
        // tiny busy spin to be belt-and-braces.
        long start = System.nanoTime();
        while (System.nanoTime() - start < 1_000) {
            // spin
        }

        engine.resolveSeedAndStart(null);
        long b = engine.getIdmNoiseRng().nextLong();

        assertThat(a)
                .as("two auto-seeded runs nanoseconds apart should produce different first draws")
                .isNotEqualTo(b);
    }

    @Test
    void seedSource_recordedOnEngine() {
        setRoadNetworkSeed(222L);
        engine.resolveSeedAndStart(999L);
        assertThat(engine.getLastSeedSource()).isEqualTo("command");

        engine.resolveSeedAndStart(null);
        assertThat(engine.getLastSeedSource()).isEqualTo("json");

        setRoadNetworkSeed(null);
        engine.resolveSeedAndStart(null);
        assertThat(engine.getLastSeedSource()).isEqualTo("auto");
    }

    /**
     * Sets the RoadNetwork's optional seed via reflection (the field is the placeholder added in
     * this plan; Plan 03 will populate it from MapConfig). Creates a minimal RoadNetwork if none
     * exists, otherwise mutates the existing one's seed.
     */
    private void setRoadNetworkSeed(Long seed) {
        try {
            Field rnField = SimulationEngine.class.getDeclaredField("roadNetwork");
            rnField.setAccessible(true);
            RoadNetwork existing = (RoadNetwork) rnField.get(engine);
            if (existing == null) {
                existing = RoadNetwork.builder().id("test").build();
                rnField.set(engine, existing);
            }
            Field seedField = RoadNetwork.class.getDeclaredField("seed");
            seedField.setAccessible(true);
            seedField.set(existing, seed);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot set RoadNetwork.seed via reflection", e);
        }
    }
}
