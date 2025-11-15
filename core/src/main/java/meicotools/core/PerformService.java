package meicotools.core;

import meico.mei.Helper;
import meico.mei.Mei;
import meico.msm.Msm;
import meico.mpm.Mpm;
import meico.mpm.elements.Dated;
import meico.mpm.elements.Global;
import meico.mpm.elements.Part;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.GenericMap;
import meico.mpm.elements.maps.MetricalAccentuationMap;
import meico.mpm.elements.maps.MovementMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.mpm.elements.maps.data.MetricalAccentuationData;
import meico.mpm.elements.maps.data.MovementData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.maps.data.TempoData;
import meico.midi.Midi;
import meico.supplementary.KeyValue;
import meicotools.core.ModifyService.Exaggerate;
import meicotools.core.ModifyService.ModifyParams;
import nu.xom.Element;
import nu.xom.Nodes;
import nu.xom.Attribute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Behavior:
 *   - Converts MEI → MSM (per movement) and reads MPM.
 *   - Applies Performance to MSM to get an expressive MSM.
 *   - If ids were passed:
 *       * Removes notes not in the set
 *       * Shifts all remaining notes' "milliseconds.date" so earliest selected note starts at t=0.
 *   - Exports expressive MIDI from the expressive MSM.
 */
public class PerformService {
    private static double getAverageTempo(TempoMap tempoMap) {
        if (tempoMap.isEmpty()) return 60.0;

        double avgTempo = 0.0;
        for (int i=0; i<tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td.isConstantTempo()) {
                avgTempo += td.bpm * td.beatLength * 4;
            }
            else {
                double meanTempoAt = td.meanTempoAt == null ? 0.5 : td.meanTempoAt;
                double frameMean = tempoMap.getTempoAt(td.startDate + meanTempoAt * (td.endDate - td.startDate));
                avgTempo += frameMean * td.beatLength * 4;
            }
        }
        avgTempo /= tempoMap.size();
        return avgTempo;
    }

    private static double getAverageDynamics(DynamicsMap dynamicsMap) {
        if (dynamicsMap.isEmpty()) return 50.0;

        double avgVolume = 0.0;
        for (int i=0; i<dynamicsMap.size(); i++) {
            DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
            if (dd.isConstantDynamics()) {
                avgVolume += dd.volume;
            }
            else {
                double frameMean = (dd.transitionTo + dd.volume) / 2.0;
                avgVolume += frameMean;
            }
        }
        avgVolume /= dynamicsMap.size();
        return avgVolume;
    }
    

    private static double[] isolateMPM(
        Performance performance,
        Set<String> mpmIDs
    ) {
        System.out.println("Isolating MPM elements by xml:id, count=" + mpmIDs.size());

        // Find all dated elements and store those whose xml:id is in mpmIDs
        final String XML_NS = "http://www.w3.org/XML/1998/namespace";
        Nodes candidates = performance.getXml().query("descendant::*[@date]");
        List<Element> selectedElements = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Element el = (Element) candidates.get(i);
            String xmlId = firstNonNull(
                el.getAttributeValue("id", XML_NS),
                el.getAttributeValue("xml:id")
            );
            if (xmlId != null && mpmIDs.contains(xmlId)) {
                selectedElements.add(el);
            }
        }
        System.out.println("Found " + selectedElements.size() + " matching elements: " + selectedElements.stream().map(e -> firstNonNull(
            e.getAttributeValue("id", XML_NS),
            e.getAttributeValue("xml:id")
        )).collect(Collectors.toList()));

        // Find the element with the smallest date
        double minDate = Double.POSITIVE_INFINITY;
        double maxDate = 0.0;

        for (Element el : selectedElements) {
            String dateStr = el.getAttributeValue("date");
            if (dateStr != null) {
            try {
                double d = Double.parseDouble(dateStr);
                if (d < minDate) {
                    minDate = d;
                }
                if (d > maxDate) {
                    maxDate = d;
                }
            } catch (Exception ignore) {}
            }
        }
        System.out.println("Starting with minDate=" + minDate + " maxDate=" + maxDate);

        // 1) Deal with <tempo>
        {
            TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
            double avgTempo = PerformService.getAverageTempo(tempoMap);

            for (int i=0; i<tempoMap.size(); i++) {
                TempoData td = tempoMap.getTempoDataOf(i);
                if (mpmIDs.contains(td.xmlId)) {
                    if (td.endDate > maxDate) {
                        System.out.println("(tempo) Adjusting maxDate from " + maxDate + " to " + td.endDate);
                        maxDate = td.endDate;
                    }
                }
                else {
                    // replace all non-selected tempo data with average tempo data
                    Element el = tempoMap.getElement(i);
                    el.addAttribute(new Attribute("bpm", Double.toString(avgTempo)));
                    el.addAttribute(new Attribute("beatLength", "0.25"));
                    Attribute transitionTo = el.getAttribute("transition.to");
                    if (transitionTo != null) {
                       el.removeAttribute(transitionTo);
                    }
                }
            }
        }

        // 2) Deal with dynamics
        {
            DynamicsMap dynamicsMap = (DynamicsMap) performance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);

            double avgDynamics = PerformService.getAverageDynamics(dynamicsMap);

            for (int i=0; i<dynamicsMap.size(); i++) {
                DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
                if (mpmIDs.contains(dd.xmlId)) {
                    if (dd.endDate > maxDate) {
                        System.out.println("(dynamics) Adjusting maxDate from " + maxDate + " to " + dd.endDate);
                        maxDate = dd.endDate;
                    }
                }
                else {
                    // replace all non-selected tempo data with average tempo data
                    Element el = dynamicsMap.getElement(i);
                    el.addAttribute(new Attribute("volume", Double.toString(avgDynamics)));
                    Attribute transitionTo = el.getAttribute("transition.to");
                    if (transitionTo != null) {
                       el.removeAttribute(transitionTo);
                    }
                }
            }
        } 

        // 2) Deal with movement
        {
            MovementMap movementMap = (MovementMap) performance.getGlobal().getDated().getMap(Mpm.MOVEMENT_MAP);
            for (int i=0; i<movementMap.size(); i++) {
                MovementData dd = movementMap.getMovementDataOf(i);
                if (mpmIDs.contains(dd.xmlId)) {
                    if (dd.endDate > maxDate) {
                        System.out.println("(movement) Adjusting maxDate from " + maxDate + " to " + dd.endDate);
                        maxDate = dd.endDate;
                    }
                }
                else {
                    // neutralize all remaining
                    Element el = movementMap.getElement(i);
                    el.addAttribute(new Attribute("position", "0"));
                    el.addAttribute(new Attribute("transition.to", "0"));
                }
            }
        }

        // 3) Deal with all other map types
        String[] mapTypes = {
            Mpm.ARTICULATION_MAP,
            Mpm.METRICAL_ACCENTUATION_MAP,
            Mpm.ORNAMENTATION_MAP,
            Mpm.RUBATO_MAP
        };

        for (String mapType : mapTypes) {
            GenericMap map = (GenericMap) performance.getGlobal().getDated().getMap(mapType);
            if (map == null) continue;
            map.sort();

            ArrayList<Integer> toRemove = new ArrayList<>();
            for (int i=0; i<map.size(); i++) {
                Element el = map.getElement(i);
                String xmlId = firstNonNull(
                    el.getAttributeValue("id", XML_NS),
                    el.getAttributeValue("xml:id")
                );
                if (xmlId == null || !mpmIDs.contains(xmlId)) {
                    toRemove.add(i);
                }
            }
            Collections.reverse(toRemove);
            for (int idx : toRemove) {
                map.removeElement(idx);
            }

            if (map.isEmpty()) continue;
 
            if (mapType == Mpm.RUBATO_MAP) {
                RubatoData rd = ((RubatoMap) map).getRubatoDataOf(map.size() - 1);
                System.out.println("The last active rubato frame ends at " + (rd.frameLength + rd.startDate) + ". Adjusting " + maxDate + "if needed.");
                maxDate = Math.max(maxDate, rd.frameLength + rd.startDate);
            }
            else if (mapType == Mpm.METRICAL_ACCENTUATION_MAP) {
                MetricalAccentuationData md = ((MetricalAccentuationMap) map).getMetricalAccentuationDataOf(map.size() - 1);
                System.out.println("MetricalAccentuationMap has " + map.size() + " entries. " + md);
                if (md == null) continue;
                System.out.println("Last MetricalAccentuationData startDate=" + md.startDate + " length=" + md.accentuationPatternDef.getLength());

                double length = (md.accentuationPatternDef.getLength() * 720 * 4) / 4.0; // 4.0 = denominator
                maxDate = Math.max(maxDate, md.startDate + length);
            }
        }

        System.out.println("Final range after isolating MPM ids: minDate=" + minDate + " maxDate=" + maxDate);

        return new double[] {minDate, maxDate};
    }

    public enum SelectionType {
        NONE,
        NOTE_IDS,
        MPM_IDS
    }

    public static void perform(
        File meiFile,
        File mpmFile,
        File rangesFile,
        File outFile,
        String[] selection,
        SelectionType selectionType,
        int ppq,
        int movementIndex,
        Double exaggerate 
    ) throws Exception {
        Set<String> keepIds = Collections.emptySet();
        keepIds = Arrays.stream(selection)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (keepIds.isEmpty()) {
            System.out.println("Warning: --ids provided but parsed empty set; ignoring.");
        }

        // 1) Load MEI
        System.out.println("Loading MEI: " + meiFile.getAbsolutePath());
        Mei mei = new Mei(meiFile);

        // 2) Convert MEI → MSM(+MPM) for all movements
        System.out.println("Converting MEI to MSM");
        KeyValue<List<Msm>, List<Mpm>> msmMpm = mei.exportMsmMpm(ppq);
        List<Msm> msms = msmMpm.getKey();

        if (msms.isEmpty()) {
            throw new IllegalStateException("No MSM movements produced from MEI.");
        }
        if (movementIndex < 0 || movementIndex >= msms.size()) {
            throw new IllegalArgumentException("Movement index out of range. Available: 0.." + (msms.size()-1));
        }
        Msm msm = msms.get(movementIndex);
        System.out.println("Selected movement index: " + movementIndex);

        msm.removeRests();
        // msm.resolveSequencingMaps();

        // 3) Load MPM and get Performance
        System.out.println("Loading MPM: " + mpmFile.getAbsolutePath());
        Mpm mpm = new Mpm(mpmFile); // Adjust to your API if different (e.g., Mpm.read(file))
        Performance performance = mpm.getPerformance(0); // Or select the appropriate Performance by id/index
        if (performance == null) {
            throw new IllegalStateException("No Performance found in MPM file.");
        }

        if (exaggerate != null) {
            ModifyParams params = new ModifyParams();
            params.exaggerate = new Exaggerate();
            params.exaggerate.tempo = exaggerate;
            params.exaggerate.dynamics = exaggerate;
            ModifyService.modify(mpm, params);
        }

        // 4) Apply Performance → expressive MSM
        System.out.println("Applying performance to MSM ...");
        Msm expressiveMsm = performance.perform(msm);

        // 5) If ids were provided: filter and shift onsets
        if (!keepIds.isEmpty()) {
            double[] range = selectionType == SelectionType.NOTE_IDS
                ? getRangeForIDs(expressiveMsm, keepIds)
                : isolateMPM(performance, keepIds);
            System.out.println("Filtering to range" + Arrays.toString(range));
            filterNotesByDate(expressiveMsm, range[0], range[1]);
            shiftOnsetsToFirstNote(expressiveMsm);
        }

        ArrayList<GenericMap> maps = new ArrayList<GenericMap>();

        ArrayList<Part> parts = performance.getAllParts();
        for (Part part : parts) {
            Dated dated = part.getDated();
            maps.addAll(dated.getAllMaps().values());
        }

        Global global = performance.getGlobal();
        Dated dated = global.getDated();
        maps.addAll(dated.getAllMaps().values());

        Map<String, Double[]> ranges = new HashMap<String, Double[]>();
        for (GenericMap map : maps) {
            for (KeyValue<Double, Element> entry : map.getAllElements()) {
                Double date = entry.getKey();
                Double phys = toPhysicalDate(date, expressiveMsm);
                Double physEnd = null;
                if (phys == null) continue;

                String elementId = firstNonNull(
                        entry.getValue().getAttributeValue("id", "http://www.w3.org/XML/1998/namespace"),
                        entry.getValue().getAttributeValue("xml:id")
                );
                if (elementId == null) continue;

                Attribute endDateAttr = entry.getValue().getAttribute("endDate");
                if (endDateAttr != null) {
                    try {
                        Double endDate = Double.parseDouble(endDateAttr.getValue());
                        physEnd = toPhysicalDate(endDate, expressiveMsm);
                    } catch (Exception ignore) {}
                }

                Double[] result = new Double[] { phys, physEnd == null ? phys : physEnd };
                ranges.put(elementId, result);
            }
        }
        // Write ranges map as a JSON object to rangesFile using Jackson
        java.util.Map<String, Object> outMap = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, Double[]> e : ranges.entrySet()) {
            Double[] v = e.getValue();
            outMap.put(e.getKey(), v);
        }

        java.io.File parent = rangesFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        try {
            mapper.writeValue(rangesFile, outMap);
            System.out.println("Wrote ranges JSON to " + rangesFile.getAbsolutePath());
        } catch (Exception ex) {
            throw new RuntimeException("Failed to write ranges JSON to " + rangesFile, ex);
        }

        // 6) Export expressive MIDI (attributes already present on expressiveMsm)
        System.out.println("Exporting expressive MSM to: " + outFile.getAbsolutePath() + ".msm");

        expressiveMsm.writeFile(outFile.getAbsolutePath() + ".msm");

        // Make sure that we always use the MIDI channel 1. 
        // Yamaha Disklavier expects this and ignores other channels.
        for (Element part = expressiveMsm.getRootElement().getFirstChildElement("part"); part != null; part = Helper.getNextSiblingElement("part", part)) {
            Attribute existing = part.getAttribute("midi.channel");
            if (existing != null) {
                System.out.println("Warning: Overriding existing midi.channel='" + existing.getValue() + "' with '0'");
                part.removeAttribute(existing);
            }
            part.addAttribute(new Attribute("midi.channel", "0"));
        }

        Midi midi = expressiveMsm.exportExpressiveMidi();

        midi.writeMidi(outFile.getAbsolutePath());
        System.out.println("Wrote MIDI: " + outFile.getAbsolutePath());

        System.out.println("Done.");
    }

    public static void perform(
        File meiFile,
        File mpmFile,
        File rangesFile,
        File outFile,
        String[] ids,
        int ppq,
        int movementIndex
    ) throws Exception {
        Set<String> keepIds = Collections.emptySet();
        keepIds = Arrays.stream(ids)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (keepIds.isEmpty()) {
            System.out.println("Warning: --ids provided but parsed empty set; ignoring.");
        }

        // 1) Load MEI
        System.out.println("Loading MEI: " + meiFile.getAbsolutePath());
        Mei mei = new Mei(meiFile);

        // 2) Convert MEI → MSM(+MPM) for all movements
        System.out.println("Converting MEI to MSM");
        KeyValue<List<Msm>, List<Mpm>> msmMpm = mei.exportMsmMpm(ppq);
        List<Msm> msms = msmMpm.getKey();

        if (msms.isEmpty()) {
            throw new IllegalStateException("No MSM movements produced from MEI.");
        }
        if (movementIndex < 0 || movementIndex >= msms.size()) {
            throw new IllegalArgumentException("Movement index out of range. Available: 0.." + (msms.size()-1));
        }
        Msm msm = msms.get(movementIndex);
        System.out.println("Selected movement index: " + movementIndex);

        msm.removeRests();
        // msm.resolveSequencingMaps();

        // 3) Load MPM and get Performance
        System.out.println("Loading MPM: " + mpmFile.getAbsolutePath());
        Mpm mpm = new Mpm(mpmFile);
        Performance performance = mpm.getPerformance(0);
        if (performance == null) {
            throw new IllegalStateException("No Performance found in MPM file.");
        }

        // 4) Apply Performance → expressive MSM
        System.out.println("Applying performance to MSM ...");
        Msm expressiveMsm = performance.perform(msm);

        // 5) If ids were provided: filter and shift onsets
        if (!keepIds.isEmpty()) {
            System.out.println("Filtering to " + keepIds.size() + " MEI ids and shifting onsets to first selected note...");
            getRangeForIDs(expressiveMsm, keepIds);
            shiftOnsetsToFirstNote(expressiveMsm);
        }

        ArrayList<GenericMap> maps = new ArrayList<GenericMap>();

        ArrayList<Part> parts = performance.getAllParts();
        for (Part part : parts) {
            Dated dated = part.getDated();
            maps.addAll(dated.getAllMaps().values());
        }

        Global global = performance.getGlobal();
        Dated dated = global.getDated();
        maps.addAll(dated.getAllMaps().values());

        Map<String, Double[]> ranges = new HashMap<String, Double[]>();
        for (GenericMap map : maps) {
            for (KeyValue<Double, Element> entry : map.getAllElements()) {
                Double date = entry.getKey();
                Double phys = toPhysicalDate(date, expressiveMsm);
                Double physEnd = null;
                if (phys == null) continue;

                String elementId = firstNonNull(
                        entry.getValue().getAttributeValue("id", "http://www.w3.org/XML/1998/namespace"),
                        entry.getValue().getAttributeValue("xml:id")
                );
                if (elementId == null) continue;

                Attribute endDateAttr = entry.getValue().getAttribute("endDate");
                if (endDateAttr != null) {
                    try {
                        Double endDate = Double.parseDouble(endDateAttr.getValue());
                        physEnd = toPhysicalDate(endDate, expressiveMsm);
                    } catch (Exception ignore) {}
                }

                Double[] result = new Double[] { phys, physEnd == null ? phys : physEnd };
                ranges.put(elementId, result);
            }
        }
        // Write ranges map as a JSON object to rangesFile using Jackson
        java.util.Map<String, Object> outMap = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, Double[]> e : ranges.entrySet()) {
            Double[] v = e.getValue();
            outMap.put(e.getKey(), v);
        }

        java.io.File parent = rangesFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        try {
            mapper.writeValue(rangesFile, outMap);
            System.out.println("Wrote ranges JSON to " + rangesFile.getAbsolutePath());
        } catch (Exception ex) {
            throw new RuntimeException("Failed to write ranges JSON to " + rangesFile, ex);
        }

        // 6) Export expressive MIDI (attributes already present on expressiveMsm)
        System.out.println("Exporting expressive MSM to: " + outFile.getAbsolutePath() + ".msm");

        expressiveMsm.writeFile(outFile.getAbsolutePath() + ".msm");

        // Make sure that we always use the MIDI channel 1. 
        // Yamaha Disklavier expects this and ignores other channels.
        for (Element part = expressiveMsm.getRootElement().getFirstChildElement("part"); part != null; part = Helper.getNextSiblingElement("part", part)) {
            Attribute existing = part.getAttribute("midi.channel");
            if (existing != null) {
                System.out.println("Warning: Overriding existing midi.channel='" + existing.getValue() + "' with '0'");
                part.removeAttribute(existing);
            }
            part.addAttribute(new Attribute("midi.channel", "0"));
        }

        Midi midi = expressiveMsm.exportExpressiveMidi();

        midi.writeMidi(outFile.getAbsolutePath());
        System.out.println("Wrote MIDI: " + outFile.getAbsolutePath());

        System.out.println("Done.");
    }

    private static void filterNotesByDate(Msm msm, double minDate, double maxDate) {
        Element root = msm.getRootElement();

        Nodes datedNodes = root.query("descendant::*[@date]");
        List<Element> toRemoveByDate = new ArrayList<>();
        for (int i = 0; i < datedNodes.size(); i++) {
            Element el = (Element) datedNodes.get(i);
            String dateStr = el.getAttributeValue("date");
            if (dateStr == null) continue;
            try {
                double d = Double.parseDouble(dateStr);
                if (d > maxDate || d < minDate) {
                    toRemoveByDate.add(el);
                }
            } catch (Exception ignore) {
            }
        }
        for (Element e : toRemoveByDate) {
            Element parent = (Element) e.getParent();
            if (parent != null) parent.removeChild(e);
        }
        System.out.println("Removed " + toRemoveByDate.size() + " elements with date > " + maxDate + " or < " + minDate);

        Nodes sectionEnd = root.query("descendant::section[@date.end]");
        for (int i = 0; i < sectionEnd.size(); i++) {
            Element sec = (Element) sectionEnd.get(i);
            String dateEndStr = sec.getAttributeValue("date.end");
            if (dateEndStr == null) continue;
            try {
                double d = Double.parseDouble(dateEndStr);
                if (d > maxDate) {
                    sec.addAttribute(new Attribute("date.end", Double.toString(maxDate)));
                }
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * Remove <note> elements whose reference to the original MEI xml:id is not in keepIds.
     */
    private static double[] getRangeForIDs(Msm msm, Set<String> keepIds) {
        Element root = msm.getRootElement();

        // XOM XPath search: find all note elements anywhere
        Nodes noteNodes = root.query("descendant::note");
        System.out.println("Total notes before filtering: " + noteNodes.size());
        List<Element> toRemove = new ArrayList<>();
        List<Double> dates = new ArrayList<>();
        for (int i = 0; i < noteNodes.size(); i++) {
            Element note = (Element) noteNodes.get(i);
            Attribute xmlId = note.getAttribute("id", "http://www.w3.org/XML/1998/namespace");
            if (xmlId == null) continue; // skip notes without xml:id

            String id = xmlId.getValue();
            if (!keepIds.contains(id)) {
                toRemove.add(note);
            }
            else {
                Attribute dateAttr = note.getAttribute("date");
                if (dateAttr != null) {
                    try {
                        dates.add(Double.parseDouble(dateAttr.getValue()));
                    } catch (Exception ignore) {}
                }
            }
        }
        for (Element e : toRemove) {
            Element parent = (Element) e.getParent();
            if (parent != null) parent.removeChild(e);
        }

        Collections.sort(dates);
        Double minDate = dates.isEmpty() ? null : dates.get(0);
        Double maxDate = dates.isEmpty() ? null : dates.get(dates.size() - 1);

        return new double[] {
            minDate == null ? 0.0 : minDate,
            maxDate == null ? 0.0 : maxDate
        };
    }

    /**
     * Shift all remaining notes' "milliseconds.date" so the earliest onset is 0.0.
     * Only updates elements that carry a "milliseconds.date" attribute (typically notes).
     */
    private static void shiftOnsetsToFirstNote(Msm msm) {
        Element root = msm.getRootElement();
        Nodes noteNodes = root.query("descendant::note[@milliseconds.date]");
        if (noteNodes.size() == 0) return;

        double minMs = Double.POSITIVE_INFINITY;
        for (int i = 0; i < noteNodes.size(); i++) {
            Element note = (Element) noteNodes.get(i);
            String msStr = note.getAttributeValue("milliseconds.date");
            try {
                double t = Double.parseDouble(msStr);
                if (t < minMs) minMs = t;
            } catch (Exception ignore) {}
        }
        if (!Double.isFinite(minMs) || minMs == 0.0) return;

        for (int i = 0; i < noteNodes.size(); i++) {
            Element note = (Element) noteNodes.get(i);
            String msStr = note.getAttributeValue("milliseconds.date");
            try {
                double t = Double.parseDouble(msStr);
                double shifted = Math.max(0.0, t - minMs);
                note.addAttribute(new nu.xom.Attribute("milliseconds.date", Double.toString(shifted)));
            } catch (Exception ignore) {}
        }
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return null;
    }

    private static Double toPhysicalDate(Double date, Msm msm) {
        Double closest = null;
        Double closestPhysical = 0.0;

        Element root = msm.getRootElement();
        Nodes notes = root.query("descendant::note[@milliseconds.date]");

        for (int i = 0; i < notes.size(); i++) {
            Element note = (Element) notes.get(i);
            String noteDate = note.getAttributeValue("date");
            if (noteDate == null) continue;

            try {
                Double d = Double.parseDouble(noteDate);
                if (closest == null || Math.abs(d - date) < Math.abs(closest - date)) {
                    closest = d;
                    closestPhysical = Double.parseDouble(note.getAttributeValue("milliseconds.date"));
                }
            } catch (Exception ignore) {}
        }

        return closestPhysical;
    }
}