package meicotools.core;

import org.junit.jupiter.api.Test;

import meicotools.core.ModifyService.Exaggerate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Exaggerate data class — pure math, no meico dependencies.
 */
public class ExaggerateTest {

    @Test
    void defaultConstructor_allFieldsAreOne() {
        Exaggerate e = new Exaggerate();
        assertEquals(1.0, e.tempo);
        assertEquals(1.0, e.dynamics);
        assertEquals(1.0, e.rubato);
        assertEquals(1.0, e.temporalSpread);
        assertEquals(1.0, e.dynamicsGradient);
        assertEquals(1.0, e.relativeVelocity);
        assertEquals(1.0, e.relativeDuration);
        assertEquals(1.0, e.accentuation);
    }

    @Test
    void valueConstructor_setsAllFields() {
        Exaggerate e = new Exaggerate(2.5);
        assertEquals(2.5, e.tempo);
        assertEquals(2.5, e.dynamics);
        assertEquals(2.5, e.rubato);
        assertEquals(2.5, e.temporalSpread);
        assertEquals(2.5, e.dynamicsGradient);
        assertEquals(2.5, e.relativeVelocity);
        assertEquals(2.5, e.relativeDuration);
        assertEquals(2.5, e.accentuation);
    }

    @Test
    void applyWeights_identityWeightsPreserveValues() {
        Exaggerate e = new Exaggerate(3.0);
        Exaggerate weights = new Exaggerate(1.0);
        e.applyWeights(weights);

        // (3.0 - 1.0) * 1.0 + 1.0 = 3.0
        assertEquals(3.0, e.tempo, 1e-9);
        assertEquals(3.0, e.dynamics, 1e-9);
    }

    @Test
    void applyWeights_zeroWeightsCollapseToOne() {
        Exaggerate e = new Exaggerate(5.0);
        Exaggerate weights = new Exaggerate();
        weights.tempo = 0.0;
        weights.dynamics = 0.0;
        weights.rubato = 0.0;
        weights.temporalSpread = 0.0;
        weights.dynamicsGradient = 0.0;
        weights.relativeVelocity = 0.0;
        weights.relativeDuration = 0.0;
        weights.accentuation = 0.0;
        e.applyWeights(weights);

        // (5.0 - 1.0) * 0.0 + 1.0 = 1.0
        assertEquals(1.0, e.tempo, 1e-9);
        assertEquals(1.0, e.dynamics, 1e-9);
        assertEquals(1.0, e.rubato, 1e-9);
    }

    @Test
    void applyWeights_halfWeightsHalveDeviation() {
        Exaggerate e = new Exaggerate(3.0);
        Exaggerate weights = new Exaggerate(0.5);
        e.applyWeights(weights);

        // (3.0 - 1.0) * 0.5 + 1.0 = 2.0
        assertEquals(2.0, e.tempo, 1e-9);
        assertEquals(2.0, e.dynamics, 1e-9);
    }

    @Test
    void applyWeights_withDefaultWeights() {
        // This mirrors what PerformService.perform() does
        Exaggerate e = new Exaggerate(2.0);
        e.applyWeights(PerformService.getDefaultWeights());

        // tempo weight = 1.0: (2.0 - 1.0) * 1.0 + 1.0 = 2.0
        assertEquals(2.0, e.tempo, 1e-9);
        // dynamics weight = 1.1: (2.0 - 1.0) * 1.1 + 1.0 = 2.1
        assertEquals(2.1, e.dynamics, 1e-9);
        // rubato weight = 0.2: (2.0 - 1.0) * 0.2 + 1.0 = 1.2
        assertEquals(1.2, e.rubato, 1e-9);
    }

    @Test
    void scale_multipliesFieldwise() {
        Exaggerate e = new Exaggerate(2.0);
        Exaggerate s = new Exaggerate();
        s.tempo = 3.0;
        s.dynamics = 0.5;
        s.rubato = 1.0;
        e.scale(s);

        assertEquals(6.0, e.tempo, 1e-9);
        assertEquals(1.0, e.dynamics, 1e-9);
        assertEquals(2.0, e.rubato, 1e-9);
    }

    @Test
    void scale_identityPreservesValues() {
        Exaggerate e = new Exaggerate(2.5);
        Exaggerate identity = new Exaggerate(1.0);
        e.scale(identity);

        assertEquals(2.5, e.tempo, 1e-9);
        assertEquals(2.5, e.dynamics, 1e-9);
        assertEquals(2.5, e.rubato, 1e-9);
    }
}
