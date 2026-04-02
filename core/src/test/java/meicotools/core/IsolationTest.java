package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;

import meico.mei.Mei;
import meico.midi.Midi;
import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.GenericMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.data.TempoData;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.msm.Msm;
import nu.xom.Element;
import nu.xom.Nodes;

import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.ShortMessage;

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
    void testMovementOnly_producesEmptyNoteRange() throws Exception {
        // Selecting only movement elements should produce an empty note range [0, 0].
        // The movements still render as MIDI CC events, but no notes are included.
        double[] range = Isolation.isolateInstructions(performance, Set.of("sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"));
        double[] expected = { 0.0, 0.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testMovementDoesNotExpandNoteRange() throws Exception {
        // Range from tempo_720 alone
        double[] tempoOnly = Isolation.isolateInstructions(performance, Set.of("tempo_720"));

        // Adding movement elements should not change the range
        double[] mixed = Isolation.isolateInstructions(performance, Set.of(
            "tempo_720", "sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"
        ));
        assertArrayEquals(tempoOnly, mixed,
            "Movement elements should not expand the note range beyond what note-driving instructions define");
    }

    @Test
    void testMovementWithDynamics_rangeFromDynamicsOnly() throws Exception {
        // dynamics_2520 starts at date 2520. The sustain-1440 movement starts at date 764.
        // Without the fix, the movement would pull minDate down to 764.
        double[] range = Isolation.isolateInstructions(performance, Set.of(
            "dynamics_2520", "sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"
        ));
        assertTrue(range[0] >= 2520.0,
            "minDate should come from dynamics (2520), not from movement (764), got: " + range[0]);
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

        // Dynamics map: dynamics_2520 selected + neutral default at date 0
        // + cap at endDate (3600) for the transition → 3 elements
        DynamicsMap dynamicsMap = (DynamicsMap) stripped.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);
        assertEquals(3, dynamicsMap.size(), "Dynamics map should have neutral default + selected element + transition cap");
        // First element should be the neutral default at date 0
        assertEquals(0.0, dynamicsMap.getDynamicsDataOf(0).startDate, "First dynamics element should be at date 0");
        // Second element should be the selected one
        Element dynEl = dynamicsMap.getElement(1);
        String dynId = dynEl.getAttributeValue("id", "http://www.w3.org/XML/1998/namespace");
        assertEquals("dynamics_2520", dynId, "Second dynamics element should be dynamics_2520");
        // Third element should be the transition cap at the original endDate
        DynamicsData capDyn = dynamicsMap.getDynamicsDataOf(2);
        assertEquals(3600.0, capDyn.startDate, 0.01, "Cap dynamics should be at original endDate 3600");

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

    // --- End-to-end MIDI output tests ---

    @Test
    void testMovementOnlySelection_midiHasNoNotesButHasCCEvents() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File meiFile = new File(classLoader.getResource("input.mei").getFile());
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());

        PerformService service = new PerformService();
        Midi midi = service.perform(
            meiFile, mpmFile,
            PerformService.SelectionType.MPM_IDS,
            Set.of("sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"),
            720, 0, null, null, false, false, true
        );

        Sequence seq = midi.getSequence();
        int noteOnCount = 0;
        int ccCount = 0;
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage) {
                    ShortMessage sm = (ShortMessage) msg;
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        noteOnCount++;
                    }
                    if (sm.getCommand() == ShortMessage.CONTROL_CHANGE) {
                        ccCount++;
                    }
                }
            }
        }

        assertEquals(0, noteOnCount,
            "Movements-only selection should produce no audible notes in MIDI");
        assertTrue(ccCount > 0,
            "Movements-only selection should still produce CC events (sustain pedal) in MIDI");
        assertTrue(service.noteIDs.isEmpty(),
            "No note IDs should be collected for a movements-only selection");
    }

    @Test
    void testMixedSelection_notesOnlyFromNoteDrivingInstructions() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File meiFile = new File(classLoader.getResource("input.mei").getFile());
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());

        // 1) Perform with tempo_720 only — baseline for expected notes
        PerformService tempoOnlyService = new PerformService();
        Midi tempoOnlyMidi = tempoOnlyService.perform(
            meiFile, mpmFile,
            PerformService.SelectionType.MPM_IDS,
            Set.of("tempo_720"),
            720, 0, null, null, false, false, true
        );

        // 2) Perform with tempo_720 + movement — should have same notes
        PerformService mixedService = new PerformService();
        Midi mixedMidi = mixedService.perform(
            meiFile, mpmFile,
            PerformService.SelectionType.MPM_IDS,
            Set.of("tempo_720", "sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"),
            720, 0, null, null, false, false, true
        );

        assertEquals(tempoOnlyService.noteIDs.size(), mixedService.noteIDs.size(),
            "Adding movements to the selection should not change which notes are included");
        assertEquals(Set.copyOf(tempoOnlyService.noteIDs), Set.copyOf(mixedService.noteIDs),
            "The exact same note IDs should be selected with or without movements");

        // Count notes in MIDI to double-check
        int tempoOnlyNotes = countNoteOns(tempoOnlyMidi.getSequence());
        int mixedNotes = countNoteOns(mixedMidi.getSequence());
        assertEquals(tempoOnlyNotes, mixedNotes,
            "MIDI note count should be identical whether movements are in the selection or not");
        assertTrue(tempoOnlyNotes > 0, "Tempo selection should produce some notes");
    }

    @Test
    void testNoteDrivingSelectionOnly_producesValidMidi() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File meiFile = new File(classLoader.getResource("input.mei").getFile());
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());

        // Verify that a pure note-driving selection still works correctly
        PerformService service = new PerformService();
        Midi midi = service.perform(
            meiFile, mpmFile,
            PerformService.SelectionType.MPM_IDS,
            Set.of("tempo_720", "dynamics_2520"),
            720, 0, null, null, false, false, true
        );

        Sequence seq = midi.getSequence();
        int noteOnCount = countNoteOns(seq);
        assertTrue(noteOnCount > 0, "Note-driving selection should produce notes in MIDI");
        assertFalse(service.noteIDs.isEmpty(), "Note IDs should be collected");
    }

    @Test
    void testStripNonSelected_tempoEndDateIsPreserved() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());

        // tempo_3600 originally has endDate=5760 (the date of the next tempo).
        // After stripping, its successor is gone — verify endDate is NOT recomputed
        // to some large sentinel value.
        Performance stripped = Isolation.stripNonSelected(mpmFile, performance, Set.of("tempo_3600"));

        TempoMap tempoMap = (TempoMap) stripped.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);

        // Should have: [neutral_avg at 0, tempo_3600 at 3600]
        TempoData td3600 = null;
        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td != null && td.startDate == 3600.0) {
                td3600 = td;
                break;
            }
        }

        assertNotNull(td3600, "tempo_3600 should be present after stripping");
        System.out.println("tempo_3600 endDate after stripping: " + td3600.endDate);
        System.out.println("tempo_3600 bpm: " + td3600.bpm + " → " + td3600.transitionTo);

        // endDate should be 5760 (original), NOT Double.MAX_VALUE or similar
        assertTrue(td3600.endDate < 100000,
            "endDate should be preserved from XML, got: " + td3600.endDate);
        assertEquals(5760.0, td3600.endDate, 0.01,
            "endDate should be 5760 (original value from XML)");
    }

    @Test
    void testIsolatedTempo_transitionShapeIsPreserved() throws Exception {
        // tempo_3600 transitions from 39.26 BPM to 47.77 BPM (getting faster).
        // Before the endDate fix, the transition was stretched to infinity,
        // making the tempo essentially constant — IOIs would be uniform.
        // After the fix, notes should get closer together as tempo increases.
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        File meiFile = new File(classLoader.getResource("input.mei").getFile());

        Msm msm2 = ConvertService.meiToMsm(meiFile, 0);
        Mpm freshMpm = new Mpm(mpmFile);
        Performance freshPerf = freshMpm.getPerformance(0);
        Performance isolated = Isolation.stripNonSelected(mpmFile, freshPerf, Set.of("tempo_3600"));
        Msm isoMsm = isolated.perform(msm2);

        double[] iois = collectIOIs(isoMsm, 3600.0, 5760.0);
        assertTrue(iois.length >= 3, "Should have enough notes for IOI comparison, got " + iois.length);

        // Find first and last non-trivial IOIs
        double firstIOI = -1, lastIOI = -1;
        for (double ioi : iois) {
            if (ioi > 1.0) { firstIOI = ioi; break; }
        }
        for (int i = iois.length - 1; i >= 0; i--) {
            if (iois[i] > 1.0) { lastIOI = iois[i]; break; }
        }

        assertTrue(firstIOI > 0 && lastIOI > 0, "Should have non-trivial IOIs");
        // Tempo increases → notes get closer → last IOI should be noticeably smaller
        assertTrue(lastIOI < firstIOI * 0.95,
            "Tempo accelerates from 39→48 BPM, so last IOI (" + lastIOI
            + ") should be smaller than first IOI (" + firstIOI + ")");
    }

    private static double[] collectIOIs(Msm msm, double minDate, double maxDate) {
        Nodes notes = msm.getRootElement().query("descendant::note[@milliseconds.date and @date]");
        java.util.List<double[]> inRange = new java.util.ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            Element note = (Element) notes.get(i);
            double date = Double.parseDouble(note.getAttributeValue("date"));
            if (date >= minDate && date < maxDate) {
                double ms = Double.parseDouble(note.getAttributeValue("milliseconds.date"));
                inRange.add(new double[]{date, ms});
            }
        }
        inRange.sort((a, b) -> Double.compare(a[1], b[1]));

        double[] iois = new double[Math.max(0, inRange.size() - 1)];
        for (int i = 1; i < inRange.size(); i++) {
            iois[i - 1] = inRange.get(i)[1] - inRange.get(i - 1)[1];
        }
        return iois;
    }

    private static int countNoteOns(Sequence seq) {
        int count = 0;
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage) {
                    ShortMessage sm = (ShortMessage) msg;
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
