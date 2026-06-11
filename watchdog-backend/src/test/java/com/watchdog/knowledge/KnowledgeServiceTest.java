package com.watchdog.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KnowledgeServiceTest {

    @Test
    void cosineSimilarityIsOneForIdenticalVectors() {
        List<Double> v = List.of(1.0, 2.0, 3.0);
        assertThat(KnowledgeService.cosineSimilarity(v, v)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosineSimilarityIsZeroForOrthogonalVectors() {
        List<Double> a = List.of(1.0, 0.0);
        List<Double> b = List.of(0.0, 1.0);
        assertThat(KnowledgeService.cosineSimilarity(a, b)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void cosineSimilarityHandlesZeroVectors() {
        List<Double> a = List.of(0.0, 0.0);
        List<Double> b = List.of(1.0, 1.0);
        assertThat(KnowledgeService.cosineSimilarity(a, b)).isEqualTo(0.0);
    }
}
