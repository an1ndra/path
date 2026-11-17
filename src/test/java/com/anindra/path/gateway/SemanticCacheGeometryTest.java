package com.anindra.path.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheGeometryTest {

    @Test
    void identicalVectorsAreMaximallySimilar() {
        double[] v = {0.6, 0.8};
        assertThat(SemanticCacheService.cosine(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void orthogonalVectorsAreNotSimilar() {
        assertThat(SemanticCacheService.cosine(new double[] {1, 0}, new double[] {0, 1}))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void magnitudeDoesNotMatterOnlyDirection() {
        double a = SemanticCacheService.cosine(new double[] {1, 1}, new double[] {1, 1});
        double b = SemanticCacheService.cosine(new double[] {10, 10}, new double[] {0.1, 0.1});
        assertThat(a).isCloseTo(b, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void mismatchedDimensionsAndZeroVectorsAreRejected() {
        assertThat(SemanticCacheService.cosine(new double[] {1, 2}, new double[] {1})).isEqualTo(-1);
        assertThat(SemanticCacheService.cosine(new double[] {0, 0}, new double[] {1, 1})).isEqualTo(-1);
        assertThat(SemanticCacheService.cosine(new double[] {}, new double[] {})).isEqualTo(-1);
    }

    @Test
    void vectorsRoundTripThroughStorage() {
        SemanticCacheService service = new SemanticCacheService(null, null, null, null);
        double[] vector = {0.1, -0.25, 3.5};
        String encoded = service.encode(vector);
        assertThat(encoded).isEqualTo("0.1,-0.25,3.5");
        assertThat(service.encode(null)).isNull();
        assertThat(service.encode(new double[] {})).isNull();
    }
}
