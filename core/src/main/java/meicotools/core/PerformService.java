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
        SelectionType selectionType,
        Set<String> keepIds,
        int ppq,
        int movementIndex,
        Double exaggerate 
    ) throws Exception {
        Msm msm = ConvertService.meiToMsm(meiFile, movementIndex);

        Mpm mpm = new Mpm(mpmFile);
        Performance performance = mpm.getPerformance(0);
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

        Msm expressiveMsm = performance.perform(msm);

        if (!keepIds.isEmpty()) {
            double[] range = selectionType == SelectionType.NOTE_IDS
                ? Isolation.isolateNotes(expressiveMsm, keepIds)
                : Isolation.isolateInstructions(performance, keepIds);
            System.out.println("Filtering to " + range);
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