package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.junit.jupiter.api.Test;

/**
 * Guards against RESEARCH.md Pitfall #3 + threat T-25-04: a misspelled algorithm name throws
 * IllegalArgumentException only at first use. This test runs every build and fails fast if {@code
 * SimulationEngine.MASTER_ALGORITHM} ever drifts to a name the JDK doesn't recognise.
 */
class RngBootstrapTest {

    @Test
    void masterAlgorithm_resolvesViaFactory() {
        RandomGeneratorFactory<RandomGenerator> factory =
                RandomGeneratorFactory.of(SimulationEngine.MASTER_ALGORITHM);
        assertThat(factory).isNotNull();
    }

    @Test
    void masterAlgorithm_isSplittable() {
        RandomGeneratorFactory<RandomGenerator> factory =
                RandomGeneratorFactory.of(SimulationEngine.MASTER_ALGORITHM);
        assertThat(factory.isSplittable())
                .as("MASTER_ALGORITHM must be splittable for sub-RNG fan-out (D-02)")
                .isTrue();
    }

    @Test
    void masterAlgorithm_seededInstanceProducesDeterministicSequence() {
        RandomGeneratorFactory<RandomGenerator> factory =
                RandomGeneratorFactory.of(SimulationEngine.MASTER_ALGORITHM);
        RandomGenerator a = factory.create(123L);
        RandomGenerator b = factory.create(123L);
        for (int i = 0; i < 10; i++) {
            assertThat(a.nextLong()).as("byte-identity at draw " + i).isEqualTo(b.nextLong());
        }
    }
}
