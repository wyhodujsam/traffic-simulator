package com.trafficsimulator.engine.kpi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LosClassifierTest {

    @Test
    void boundaryValues_perD07() {
        assertThat(LosClassifier.classify(0)).isEqualTo("A");
        assertThat(LosClassifier.classify(7)).isEqualTo("A");
        assertThat(LosClassifier.classify(7.01)).isEqualTo("B");
        assertThat(LosClassifier.classify(11)).isEqualTo("B");
        assertThat(LosClassifier.classify(11.01)).isEqualTo("C");
        assertThat(LosClassifier.classify(16)).isEqualTo("C");
        assertThat(LosClassifier.classify(16.01)).isEqualTo("D");
        assertThat(LosClassifier.classify(22)).isEqualTo("D");
        assertThat(LosClassifier.classify(22.01)).isEqualTo("E");
        assertThat(LosClassifier.classify(28)).isEqualTo("E");
        assertThat(LosClassifier.classify(28.01)).isEqualTo("F");
        assertThat(LosClassifier.classify(100)).isEqualTo("F");
    }

    @Test
    void worse_picksHigherLetter() {
        assertThat(LosClassifier.worse("A", "F")).isEqualTo("F");
        assertThat(LosClassifier.worse("D", "B")).isEqualTo("D");
        assertThat(LosClassifier.worse(null, "C")).isEqualTo("C");
        assertThat(LosClassifier.worse("E", null)).isEqualTo("E");
    }
}
