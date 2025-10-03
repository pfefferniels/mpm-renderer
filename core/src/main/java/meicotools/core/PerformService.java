package meicotools.core;

import meico.mei.Mei;
import meico.msm.Msm;
import meico.mpm.Mpm;
import meico.mpm.elements.Dated;
import meico.mpm.elements.Global;
import meico.mpm.elements.Part;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.GenericMap;
import meico.midi.Midi;
import meico.supplementary.KeyValue;

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
 *       * Removes notes not in the set (by checking note's reference to original MEI id, e.g., "mei.id").
 *       * Shifts all remaining notes' "milliseconds.date" so earliest selected note starts at t=0.
 *   - Exports expressive MIDI from the expressive MSM.
 */
public class PerformService {
    public static void perform(File meiFile, File mpmFile, File rangesFile, File outFile, String[] ids, int ppq, int movementIndex) throws Exception {
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
        Mpm mpm = new Mpm(mpmFile); // Adjust to your API if different (e.g., Mpm.read(file))
        Performance performance = mpm.getPerformance(0); // Or select the appropriate Performance by id/index
        if (performance == null) {
            throw new IllegalStateException("No Performance found in MPM file.");
        }

        // 4) Apply Performance → expressive MSM
        System.out.println("Applying performance to MSM ...");
        Msm expressiveMsm = performance.perform(msm);

        // 5) If ids were provided: filter and shift onsets
        if (!keepIds.isEmpty()) {
            System.out.println("Filtering to " + keepIds.size() + " MEI ids and shifting onsets to first selected note...");
            filterNotesByMeiIds(expressiveMsm, keepIds);
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
        System.out.println("Exporting expressive MIDI ...");

        expressiveMsm.writeFile(outFile.getAbsolutePath() + ".msm");

        Midi midi = expressiveMsm.exportExpressiveMidi();

        midi.writeMidi(outFile.getAbsolutePath());
        System.out.println("Wrote MIDI: " + outFile.getAbsolutePath());

        System.out.println("Done.");
    }

    private static void printUsageAndExit(String msg) {
        if (msg != null && !msg.isEmpty()) System.err.println("Error: " + msg);
        System.err.println("Usage:");
        System.err.println("  java tools.Perform --mei <file.mei> --mpm <file.mpm> --out <out.midi>");
        System.err.println("Options:");
        System.err.println("  --ids <id1,id2,...>    Filter to given MEI xml:ids; shift onsets to first selected note");
        System.err.println("  --ppq <int>            PPQ for MSM conversion (default 720)");
        System.err.println("  --movement <int>       Movement index to render (default 0)");
        System.err.println("  --ignore-expansions    Do not apply MEI expansion processing");
        System.err.println("  --use-channel-10       Allow MIDI channel 10 (drums); default is to not use it");
        System.err.println("  --soundfont <file>     SF2/SF3/DLS soundbank for synthesis");
        System.exit(2);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--mei":
                case "--mpm":
                case "--out":
                case "--ids":
                case "--ranges":
                case "--soundfont":
                    if (i + 1 >= args.length) printUsageAndExit("Missing value for " + a);
                    m.put(a.substring(2), args[++i]);
                    break;
                default:
                    printUsageAndExit("Unknown arg: " + a);
            }
        }
        return m;
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    /**
     * Remove <note> elements whose reference to the original MEI xml:id is not in keepIds.
     * Adjust the attribute keys ("mei.id", "mei.note.id", etc.) to match your MSM schema.
     */
    private static void filterNotesByMeiIds(Msm msm, Set<String> keepIds) {
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
        System.out.println("minDate: " + minDate + ", maxDate: " + maxDate);

        Nodes datedNodes = root.query("descendant::*[@date]");
        List<Element> toRemoveByDate = new ArrayList<>();
        for (int i = 0; i < datedNodes.size(); i++) {
            Element el = (Element) datedNodes.get(i);
            String dateStr = el.getAttributeValue("date");
            if (dateStr == null) continue;
            try {
                double d = Double.parseDouble(dateStr);
                if (maxDate != null && d > maxDate) {
                    toRemoveByDate.add(el);
                }
            } catch (Exception ignore) {
            }
        }
        for (Element e : toRemoveByDate) {
            Element parent = (Element) e.getParent();
            if (parent != null) parent.removeChild(e);
        }
        System.out.println("Removed " + toRemoveByDate.size() + " elements with date > maxDate (" + maxDate + ")");

        Nodes sectionEnd = root.query("descendant::section[@date.end]");
        for (int i = 0; i < sectionEnd.size(); i++) {
            Element sec = (Element) sectionEnd.get(i);
            String dateEndStr = sec.getAttributeValue("date.end");
            if (dateEndStr == null) continue;
            try {
                double d = Double.parseDouble(dateEndStr);
                if (maxDate != null && d > maxDate) {
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