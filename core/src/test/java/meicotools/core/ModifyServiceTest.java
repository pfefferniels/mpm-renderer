package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.maps.data.TempoData;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

/**
 * Tests for ModifyService scaling and exaggeration on real MPM maps.
 */
public class ModifyServiceTest {
    private Performance performance;

    @BeforeEach
    void loadPerformance() throws Exception {
        ClassLoader cl = ModifyServiceTest.class.getClassLoader();
        File mpmFile = new File(cl.getResource("input.mpm").getFile());
        Mpm mpm = new Mpm(mpmFile);
        performance = mpm.getPerformance(0);
    }

    // --- scaleTempo ---

    @Test
    void scaleTempo_doublesAllBpmValues() {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        double[] originalBpms = collectBpms(tempoMap);

        ModifyService.scaleTempo(tempoMap, 2.0);

        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td == null || td.bpm == null) continue;
            assertEquals(originalBpms[i] * 2.0, td.bpm, 1e-6,
                "BPM at index " + i + " should be doubled");
        }
    }

    @Test
    void scaleTempo_factorOfOne_noChange() {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        double[] originalBpms = collectBpms(tempoMap);

        ModifyService.scaleTempo(tempoMap, 1.0);

        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td == null || td.bpm == null) continue;
            assertEquals(originalBpms[i], td.bpm, 1e-6);
        }
    }

    // --- scaleDynamics ---

    @Test
    void scaleDynamics_halvesAllVolumes() {
        DynamicsMap dynamicsMap = (DynamicsMap) performance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);
        double[] originalVols = collectVolumes(dynamicsMap);

        ModifyService.scaleDynamics(dynamicsMap, 0.5);

        for (int i = 0; i < dynamicsMap.size(); i++) {
            DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
            if (dd == null || dd.volume == null) continue;
            assertEquals(originalVols[i] * 0.5, dd.volume, 1e-6,
                "Volume at index " + i + " should be halved");
        }
    }

    // --- exaggerateTempo (log-space scaling) ---

    @Test
    void exaggerateTempo_scaleGreaterThanOne_widensDifference() {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);

        // Find a tempo entry with a transition (bpm != transitionTo)
        int transIdx = findTempoWithTransition(tempoMap);
        if (transIdx < 0) return; // skip if no transition exists

        TempoData before = tempoMap.getTempoDataOf(transIdx);
        double gapBefore = Math.abs(Math.log(before.bpm) - Math.log(before.transitionTo));

        ModifyService.exaggerateTempo(tempoMap, 2.0);

        TempoData after = tempoMap.getTempoDataOf(transIdx);
        double gapAfter = Math.abs(Math.log(after.bpm) - Math.log(after.transitionTo));

        assertTrue(gapAfter > gapBefore,
            "Log-space gap should widen with scale > 1 (before=" + gapBefore + " after=" + gapAfter + ")");
    }

    @Test
    void exaggerateTempo_scaleLessThanOne_narrowsDifference() {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);

        int transIdx = findTempoWithTransition(tempoMap);
        if (transIdx < 0) return;

        TempoData before = tempoMap.getTempoDataOf(transIdx);
        double gapBefore = Math.abs(Math.log(before.bpm) - Math.log(before.transitionTo));

        ModifyService.exaggerateTempo(tempoMap, 0.5);

        TempoData after = tempoMap.getTempoDataOf(transIdx);
        double gapAfter = Math.abs(Math.log(after.bpm) - Math.log(after.transitionTo));

        assertTrue(gapAfter < gapBefore,
            "Log-space gap should narrow with scale < 1 (before=" + gapBefore + " after=" + gapAfter + ")");
    }

    @Test
    void exaggerateTempo_preservesGeometricMean() {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);

        int transIdx = findTempoWithTransition(tempoMap);
        if (transIdx < 0) return;

        TempoData before = tempoMap.getTempoDataOf(transIdx);
        double meanBefore = Math.sqrt(before.bpm * before.transitionTo);

        ModifyService.exaggerateTempo(tempoMap, 2.0);

        TempoData after = tempoMap.getTempoDataOf(transIdx);
        double meanAfter = Math.sqrt(after.bpm * after.transitionTo);

        assertEquals(meanBefore, meanAfter, 0.5,
            "Geometric mean should be preserved by log-space scaling");
    }

    @Test
    void exaggerateTempo_clampsToMusicalBounds() {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);

        // Extreme exaggeration should not produce values outside [10, 400] BPM
        ModifyService.exaggerateTempo(tempoMap, 100.0);

        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td == null || td.bpm == null) continue;
            assertTrue(td.bpm >= 10.0 && td.bpm <= 400.0,
                "BPM should be clamped to [10, 400], got: " + td.bpm);
            if (td.transitionTo != null) {
                assertTrue(td.transitionTo >= 10.0 && td.transitionTo <= 400.0,
                    "Transition BPM should be clamped to [10, 400], got: " + td.transitionTo);
            }
        }
    }

    // --- exaggerateDynamics (log-space scaling) ---

    @Test
    void exaggerateDynamics_clampsToMidiRange() {
        DynamicsMap dynamicsMap = (DynamicsMap) performance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);

        ModifyService.exaggerateDynamics(dynamicsMap, 100.0);

        for (int i = 0; i < dynamicsMap.size(); i++) {
            DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
            if (dd == null || dd.volume == null) continue;
            assertTrue(dd.volume >= 1.0 && dd.volume <= 127.0,
                "Volume should be clamped to [1, 127], got: " + dd.volume);
        }
    }

    // --- exaggerateRubatoIntensity (power-based scaling) ---

    @Test
    void exaggerateRubato_scaleOfOne_noChange() {
        RubatoMap rubatoMap = (RubatoMap) performance.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);
        double[] originalIntensities = collectRubatoIntensities(rubatoMap);

        ModifyService.exaggerateRubatoIntensity(rubatoMap, 1.0);

        for (int i = 0; i < rubatoMap.size(); i++) {
            RubatoData rd = rubatoMap.getRubatoDataOf(i);
            if (rd == null || rd.intensity == null) continue;
            assertEquals(originalIntensities[i], rd.intensity, 1e-6,
                "Scale of 1.0 (x^1) should be identity");
        }
    }

    @Test
    void exaggerateRubato_scaleGreaterThanOne_increasesAboveOne() {
        RubatoMap rubatoMap = (RubatoMap) performance.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);

        // Find an entry with intensity > 1
        int idx = -1;
        for (int i = 0; i < rubatoMap.size(); i++) {
            RubatoData rd = rubatoMap.getRubatoDataOf(i);
            if (rd != null && rd.intensity != null && rd.intensity > 1.0) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;

        double before = rubatoMap.getRubatoDataOf(idx).intensity;
        ModifyService.exaggerateRubatoIntensity(rubatoMap, 2.0);
        double after = rubatoMap.getRubatoDataOf(idx).intensity;

        // x^2 > x when x > 1
        assertTrue(after > before,
            "For intensity > 1, scale 2 should increase it (before=" + before + " after=" + after + ")");
    }

    @Test
    void exaggerateRubato_clampsToRubatoRange() {
        RubatoMap rubatoMap = (RubatoMap) performance.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);

        ModifyService.exaggerateRubatoIntensity(rubatoMap, 50.0);

        for (int i = 0; i < rubatoMap.size(); i++) {
            RubatoData rd = rubatoMap.getRubatoDataOf(i);
            if (rd == null || rd.intensity == null) continue;
            assertTrue(rd.intensity >= 0.1 && rd.intensity <= 5.0,
                "Rubato intensity should be clamped to [0.1, 5.0], got: " + rd.intensity);
        }
    }

    // --- modify (integration) ---

    @Test
    void modify_withExaggerateParams_changesPerformance() throws Exception {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        double[] bpmsBefore = collectBpms(tempoMap);

        ModifyService.ModifyParams params = new ModifyService.ModifyParams();
        params.exaggerate = new ModifyService.Exaggerate(2.0);
        params.exaggerate.applyWeights(PerformService.getDefaultWeights());

        ModifyService.modify(performance, params);

        // After modify, tempo values should have changed (exaggeration applied)
        boolean changed = false;
        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td == null || td.bpm == null) continue;
            if (Math.abs(td.bpm - bpmsBefore[i]) > 0.01) {
                changed = true;
                break;
            }
        }
        assertTrue(changed, "Modify with exaggeration should change tempo values");
    }

    // --- helpers ---

    private double[] collectBpms(TempoMap tempoMap) {
        double[] bpms = new double[tempoMap.size()];
        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            bpms[i] = (td != null && td.bpm != null) ? td.bpm : 0;
        }
        return bpms;
    }

    private double[] collectVolumes(DynamicsMap dynamicsMap) {
        double[] vols = new double[dynamicsMap.size()];
        for (int i = 0; i < dynamicsMap.size(); i++) {
            DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
            vols[i] = (dd != null && dd.volume != null) ? dd.volume : 0;
        }
        return vols;
    }

    private double[] collectRubatoIntensities(RubatoMap rubatoMap) {
        double[] intensities = new double[rubatoMap.size()];
        for (int i = 0; i < rubatoMap.size(); i++) {
            RubatoData rd = rubatoMap.getRubatoDataOf(i);
            intensities[i] = (rd != null && rd.intensity != null) ? rd.intensity : 0;
        }
        return intensities;
    }

    private int findTempoWithTransition(TempoMap tempoMap) {
        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td != null && td.bpm != null && td.transitionTo != null
                    && td.bpm > 0 && td.transitionTo > 0) {
                return i;
            }
        }
        return -1;
    }
}
