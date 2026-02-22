package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meicotools.core.ModifyService.Exaggerate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Set;

/**
 * Tests for Shader.bringOut — verifies the type-to-field mapping works correctly.
 */
public class ShaderTest {
    private Performance performance;

    @BeforeEach
    void loadPerformance() throws Exception {
        ClassLoader cl = ShaderTest.class.getClassLoader();
        File mpmFile = new File(cl.getResource("input.mpm").getFile());
        Mpm mpm = new Mpm(mpmFile);
        performance = mpm.getPerformance(0);
    }

    @Test
    void bringOut_emptyIds_allFieldsDefault() {
        Exaggerate result = Shader.bringOut(performance, Set.of(), 0.1);
        assertEquals(1.0, result.tempo);
        assertEquals(1.0, result.dynamics);
        assertEquals(1.0, result.rubato);
        assertEquals(1.0, result.accentuation);
        assertEquals(1.0, result.temporalSpread);
        assertEquals(1.0, result.dynamicsGradient);
        assertEquals(1.0, result.relativeVelocity);
        assertEquals(1.0, result.relativeDuration);
    }

    @Test
    void bringOut_tempoId_tempoFieldExcluded() {
        // tempo_720 is a <tempo> element — the "tempo" field should NOT be reduced
        Exaggerate result = Shader.bringOut(performance, Set.of("tempo_720"), 0.1);

        assertEquals(1.0, result.tempo, "Tempo field should not be reduced for a tempo element");
        assertEquals(0.1, result.dynamics, "Other fields should be reduced");
        assertEquals(0.1, result.rubato);
    }

    @Test
    void bringOut_dynamicsId_dynamicsFieldExcluded() {
        Exaggerate result = Shader.bringOut(performance, Set.of("dynamics_2520"), 0.1);

        assertEquals(1.0, result.dynamics, "Dynamics field should not be reduced");
        assertEquals(0.1, result.tempo, "Tempo should be reduced");
    }

    @Test
    void bringOut_rubatoId_rubatoFieldExcluded() {
        Exaggerate result = Shader.bringOut(performance, Set.of("rubato_3600"), 0.1);

        assertEquals(1.0, result.rubato, "Rubato field should not be reduced");
        assertEquals(0.1, result.tempo, "Tempo should be reduced");
        assertEquals(0.1, result.dynamics, "Dynamics should be reduced");
    }

    @Test
    void bringOut_multipleTypes_multipleFieldsExcluded() {
        Exaggerate result = Shader.bringOut(performance,
            Set.of("tempo_720", "dynamics_2520"), 0.1);

        assertEquals(1.0, result.tempo, "Tempo should not be reduced");
        assertEquals(1.0, result.dynamics, "Dynamics should not be reduced");
        assertEquals(0.1, result.rubato, "Rubato should be reduced");
    }

    @Test
    void bringOut_nonexistentId_allFieldsReduced() {
        Exaggerate result = Shader.bringOut(performance, Set.of("nonexistent_id"), 0.5);

        // No element found → all fields reduced
        assertEquals(0.5, result.tempo);
        assertEquals(0.5, result.dynamics);
        assertEquals(0.5, result.rubato);
        assertEquals(0.5, result.accentuation);
        assertEquals(0.5, result.temporalSpread);
        assertEquals(0.5, result.dynamicsGradient);
        assertEquals(0.5, result.relativeVelocity);
        assertEquals(0.5, result.relativeDuration);
    }

    @Test
    void bringOut_factorIsAppliedCorrectly() {
        Exaggerate result = Shader.bringOut(performance, Set.of("tempo_720"), 0.3);

        assertEquals(1.0, result.tempo, "Matched field stays at 1.0");
        assertEquals(0.3, result.dynamics, "Non-matched fields get the factor value");
    }
}
