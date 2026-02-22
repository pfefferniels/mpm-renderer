package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;

import meico.mei.Mei;
import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.GenericMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.data.TempoData;
import meico.msm.Msm;
import nu.xom.Element;
import nu.xom.Nodes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Set;

/**
 * Test class for Isolation.
 */
public class IsolationTest {
    private static Performance performance;
    private static Mei mei;
    private static Msm msm;

    @BeforeAll
    static void loadMEI() throws Exception{
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        
        File meiFile = new File(classLoader.getResource("input.mei").getFile());
        mei = new Mei(meiFile);
        msm = ConvertService.meiToMsm(meiFile, 0);
    }

    @BeforeEach
    void loadPerformance() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        Mpm mpm = new Mpm(mpmFile);
        performance = mpm.getPerformance(0);
    }

    @Test
    void testTempo() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("tempo_720"));
        double[] expected = { 720.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testDynamics() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("dynamics_2520"));
        double[] expected = { 2520.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testRubato() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("rubato_3600"));
        double[] expected = { 3600.0, 4320.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testAccentuation() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("accentuationPattern_2880"));
        double[] expected = { 2880.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test 
    void testMovement() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"));
        double[] expected = { 764.0, 3112.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testCombination() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("tempo_720", "dynamics_2520"));
        double[] expected = { 720.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testWeightedAverages() throws Exception {
        TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        double avgTempo = Isolation.computeWeightedAverageTempo(tempoMap);
        assertTrue(Double.isFinite(avgTempo), "Tempo average should be finite, got: " + avgTempo);

        DynamicsMap dynamicsMap = (DynamicsMap) performance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);
        double avgDynamics = Isolation.computeWeightedAverageDynamics(dynamicsMap);
        assertTrue(Double.isFinite(avgDynamics), "Dynamics average should be finite, got: " + avgDynamics);
    }

    @Test
    void testStripNonSelected_mapsAreCorrectlyStripped() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());

        Set<String> keepIds = Set.of("rubato_3600", "dynamics_2520");
        Performance stripped = Isolation.stripNonSelected(mpmFile, performance, keepIds);

        // Tempo map: no tempo IDs selected → should have exactly 1 neutral default at date 0
        TempoMap tempoMap = (TempoMap) stripped.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        assertEquals(1, tempoMap.size(), "Tempo map should have 1 neutral element");
        TempoData neutralTempo = tempoMap.getTempoDataOf(0);
        assertTrue(Double.isFinite(neutralTempo.bpm), "Neutral tempo BPM should be finite");
        assertEquals(0.0, neutralTempo.startDate, "Neutral tempo should start at date 0");

        // Dynamics map: dynamics_2520 selected + neutral default at date 0 → 2 elements
        DynamicsMap dynamicsMap = (DynamicsMap) stripped.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);
        assertEquals(2, dynamicsMap.size(), "Dynamics map should have neutral default + selected element");
        // First element should be the neutral default at date 0
        assertEquals(0.0, dynamicsMap.getDynamicsDataOf(0).startDate, "First dynamics element should be at date 0");
        // Second element should be the selected one
        Element dynEl = dynamicsMap.getElement(1);
        String dynId = dynEl.getAttributeValue("id", "http://www.w3.org/XML/1998/namespace");
        assertEquals("dynamics_2520", dynId, "Second dynamics element should be dynamics_2520");

        // Rubato map: rubato_3600 selected → should have exactly 1 element
        RubatoMap rubatoMap = (RubatoMap) stripped.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);
        assertEquals(1, rubatoMap.size(), "Rubato map should have 1 selected element");
        Element rubEl = rubatoMap.getElement(0);
        String rubId = rubEl.getAttributeValue("id", "http://www.w3.org/XML/1998/namespace");
        assertEquals("rubato_3600", rubId, "Remaining rubato element should be rubato_3600");

        // Accentuation map: no accentuation IDs selected, but <style> element should be preserved
        GenericMap accentMap = stripped.getGlobal().getDated().getMap(Mpm.METRICAL_ACCENTUATION_MAP);
        assertEquals(1, accentMap.size(), "Accentuation map should have 1 style element preserved");
        assertEquals("style", accentMap.getElement(0).getLocalName(), "Preserved element should be a <style> switch");
    }

    @Test
    void testStripNonSelected_strippedPerformanceProducesValidMidi() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        File meiFile = new File(classLoader.getResource("input.mei").getFile());
        Msm freshMsm = ConvertService.meiToMsm(meiFile, 0);

        // Perform with only a rubato element selected
        Performance stripped = Isolation.stripNonSelected(mpmFile, performance, Set.of("rubato_3600"));
        Msm expressiveMsm = stripped.perform(freshMsm);

        Nodes notes = expressiveMsm.getRootElement().query("descendant::note[@milliseconds.date]");
        assertTrue(notes.size() > 0, "Should have notes with milliseconds.date after perform");

        // All note timings should be finite (no Infinity from broken averages)
        for (int i = 0; i < notes.size(); i++) {
            Element note = (Element) notes.get(i);
            double ms = Double.parseDouble(note.getAttributeValue("milliseconds.date"));
            assertTrue(Double.isFinite(ms), "Note timing should be finite, got: " + ms);
        }

        // All velocities should be finite and in valid MIDI range
        Nodes velocityNotes = expressiveMsm.getRootElement().query("descendant::note[@velocity]");
        for (int i = 0; i < velocityNotes.size(); i++) {
            Element note = (Element) velocityNotes.get(i);
            double vel = Double.parseDouble(note.getAttributeValue("velocity"));
            assertTrue(Double.isFinite(vel), "Velocity should be finite, got: " + vel);
        }
    }

    @Test
    void testStripNonSelected_selectedTempoAffectsOutput() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        File meiFile = new File(classLoader.getResource("input.mei").getFile());

        // 1) Perform with ONLY neutral defaults (select a non-existent ID to empty all maps)
        Msm msm1 = ConvertService.meiToMsm(meiFile, 0);
        Performance neutral = Isolation.stripNonSelected(mpmFile, performance, Set.of("nonexistent_id"));
        Msm neutralMsm = neutral.perform(msm1);

        // 2) Perform with tempo_720 selected (a tempo instruction with distinct BPM)
        Msm msm2 = ConvertService.meiToMsm(meiFile, 0);
        Mpm freshMpm = new Mpm(mpmFile);
        Performance freshPerf = freshMpm.getPerformance(0);
        Performance withTempo = Isolation.stripNonSelected(mpmFile, freshPerf, Set.of("tempo_720"));
        Msm tempoMsm = withTempo.perform(msm2);

        // 3) Compare note timings — the selected tempo instruction should produce
        //    measurably different timing in its active region (date >= 720)
        Nodes neutralNotes = neutralMsm.getRootElement().query("descendant::note[@milliseconds.date]");
        Nodes tempoNotes = tempoMsm.getRootElement().query("descendant::note[@milliseconds.date]");

        boolean foundDifference = false;
        for (int i = 0; i < Math.min(neutralNotes.size(), tempoNotes.size()); i++) {
            Element nNote = (Element) neutralNotes.get(i);
            Element tNote = (Element) tempoNotes.get(i);
            double nMs = Double.parseDouble(nNote.getAttributeValue("milliseconds.date"));
            double tMs = Double.parseDouble(tNote.getAttributeValue("milliseconds.date"));
            if (Math.abs(nMs - tMs) > 0.01) {
                foundDifference = true;
                break;
            }
        }

        assertTrue(foundDifference, "Selected tempo instruction should produce different timings than neutral defaults");
    }

    @Test
    void testRemoveAllRubatoAffectsPerformance() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        File meiFile = new File(classLoader.getResource("input.mei").getFile());

        // Full performance
        Msm msm1 = ConvertService.meiToMsm(meiFile, 0);
        Mpm mpm1 = new Mpm(mpmFile);
        Performance fullPerf = mpm1.getPerformance(0);
        Msm fullMsm = fullPerf.perform(msm1);

        // Performance with ALL rubato removed
        Msm msm2 = ConvertService.meiToMsm(meiFile, 0);
        Mpm mpm2 = new Mpm(mpmFile);
        Performance noRubatoPerf = mpm2.getPerformance(0);
        RubatoMap rubatoMap = (RubatoMap) noRubatoPerf.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);
        for (int i = rubatoMap.size() - 1; i >= 0; i--) {
            rubatoMap.removeElement(i);
        }

        Msm noRubatoMsm = noRubatoPerf.perform(msm2);

        Nodes fullNotes = fullMsm.getRootElement().query("descendant::note[@milliseconds.date]");
        Nodes noRubNotes = noRubatoMsm.getRootElement().query("descendant::note[@milliseconds.date]");

        int diffCount = 0;
        for (int i = 0; i < Math.min(fullNotes.size(), noRubNotes.size()); i++) {
            Element fNote = (Element) fullNotes.get(i);
            Element nNote = (Element) noRubNotes.get(i);
            double fMs = Double.parseDouble(fNote.getAttributeValue("milliseconds.date"));
            double nMs = Double.parseDouble(nNote.getAttributeValue("milliseconds.date"));
            if (Math.abs(fMs - nMs) > 0.001) diffCount++;
        }

        assertTrue(diffCount > 0, "Removing ALL rubato should change note timings — " +
            "if this fails, removeElement does not affect perform()");
    }

    @Test
    void testRemoveElementActuallyRemovesFromXml() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        Mpm mpm = new Mpm(mpmFile);
        Performance perf = mpm.getPerformance(0);
        RubatoMap rubatoMap = (RubatoMap) perf.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);

        int sizeBefore = rubatoMap.size();
        Nodes rubatoXmlBefore = perf.getXml().query("descendant::rubato");

        rubatoMap.removeElement(0);

        int sizeAfter = rubatoMap.size();
        Nodes rubatoXmlAfter = perf.getXml().query("descendant::rubato");

        assertEquals(sizeBefore - 1, sizeAfter, "Internal list should shrink");
        assertEquals(rubatoXmlBefore.size() - 1, rubatoXmlAfter.size(),
            "XML tree should also shrink — if this fails, removeElement doesn't touch XML!");
    }

    @Test
    void testStripNonSelected_preservesStyleElements() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());

        // Select only a tempo element — ornamentation and accentuation maps should
        // still retain their <style> elements for style resolution
        Performance stripped = Isolation.stripNonSelected(mpmFile, performance, Set.of("tempo_720"));

        GenericMap ornMap = stripped.getGlobal().getDated().getMap(Mpm.ORNAMENTATION_MAP);
        if (ornMap != null) {
            boolean hasStyle = false;
            for (int i = 0; i < ornMap.size(); i++) {
                if ("style".equals(ornMap.getElement(i).getLocalName())) {
                    hasStyle = true;
                    break;
                }
            }
            assertTrue(hasStyle, "Ornamentation map should preserve its <style> element");
        }

        GenericMap accMap = stripped.getGlobal().getDated().getMap(Mpm.METRICAL_ACCENTUATION_MAP);
        if (accMap != null) {
            boolean hasStyle = false;
            for (int i = 0; i < accMap.size(); i++) {
                if ("style".equals(accMap.getElement(i).getLocalName())) {
                    hasStyle = true;
                    break;
                }
            }
            assertTrue(hasStyle, "Accentuation map should preserve its <style> element");
        }
    }

    @Test
    void testStripNonSelected_selectedDynamicsAffectOutput() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        File meiFile = new File(classLoader.getResource("input.mei").getFile());

        // 1) Neutral (no dynamics selected → neutral default)
        Msm msm1 = ConvertService.meiToMsm(meiFile, 0);
        Performance neutral = Isolation.stripNonSelected(mpmFile, performance, Set.of("nonexistent_id"));
        Msm neutralMsm = neutral.perform(msm1);

        // 2) With dynamics_2520 selected
        Msm msm2 = ConvertService.meiToMsm(meiFile, 0);
        Mpm freshMpm = new Mpm(mpmFile);
        Performance freshPerf = freshMpm.getPerformance(0);
        Performance withDyn = Isolation.stripNonSelected(mpmFile, freshPerf, Set.of("dynamics_2520"));
        Msm dynMsm = withDyn.perform(msm2);

        Nodes neutralNotes = neutralMsm.getRootElement().query("descendant::note[@velocity]");
        Nodes dynNotes = dynMsm.getRootElement().query("descendant::note[@velocity]");

        boolean foundDifference = false;
        for (int i = 0; i < Math.min(neutralNotes.size(), dynNotes.size()); i++) {
            Element nNote = (Element) neutralNotes.get(i);
            Element dNote = (Element) dynNotes.get(i);
            double nVel = Double.parseDouble(nNote.getAttributeValue("velocity"));
            double dVel = Double.parseDouble(dNote.getAttributeValue("velocity"));
            if (Math.abs(nVel - dVel) > 0.01) {
                foundDifference = true;
                break;
            }
        }

        assertTrue(foundDifference, "Selected dynamics should produce different velocities than neutral");
    }

    @Test
    void testMeasureIsolation() throws Exception {
        // Format is "measureIndex/staff" — index is 0-based, code adds 1 to get MEI @n
        // Use measures 2 and 3 (indices 1 and 2) since measure 1 is an anacrusis
        double[] range = Isolation.isolateMeasures(mei, msm, Set.of("1/1", "2/1"));
        assertTrue(range[0] < range[1], "Range start should be before end");
        assertTrue(Double.isFinite(range[0]) && Double.isFinite(range[1]), "Range should be finite");
    }
}
