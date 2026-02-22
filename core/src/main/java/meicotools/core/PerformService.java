package meicotools.core;

import meico.mei.Helper;
import meico.mei.Mei;
import meico.msm.Msm;
import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.midi.Midi;
import meicotools.core.ModifyService.Exaggerate;
import meicotools.core.ModifyService.Increase;
import meicotools.core.ModifyService.ModifyParams;
import nu.xom.Element;
import nu.xom.Nodes;
import nu.xom.Attribute;

import java.io.File;
import java.util.*;

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
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final double MAX_EXEMPLIFY_DURATION = 5760.0;
    private static final double CONTEXT_AMOUNT = 0.375;

    public enum SelectionType {
        NONE,
        NOTE_IDS,
        MPM_IDS,
        MEASURES,
        RANGE
    }

    public static Exaggerate getDefaultWeights() {
        Exaggerate weights = new Exaggerate();
        weights.tempo = 1.0;
        weights.dynamics = 1.1;
        weights.rubato = 0.2;
        weights.accentuation = 1.3;
        weights.temporalSpread = 1.5;
        weights.dynamicsGradient = 0.3;
        weights.relativeDuration = 0.2;
        weights.relativeVelocity = 0.3;
        return weights;
    }

    public ArrayList<String> noteIDs = new ArrayList<String>();

    public Midi perform(
        File meiFile,
        File mpmFile,
        SelectionType selectionType,
        Set<String> keepIds,
        int ppq,
        int movementIndex,
        Double exaggerate,
        Double sketchiness,
        Boolean exemplify,
        Boolean context,
        Boolean isolate
    ) throws Exception {
        Mei mei = new Mei(meiFile);
        Msm msm = ConvertService.meiToMsm(meiFile, movementIndex);

        Mpm mpm = new Mpm(mpmFile);
        Performance performance = mpm.getPerformance(0);
        if (performance == null) {
            throw new IllegalStateException("No Performance found in MPM file.");
        }

        // In isolation mode, strip non-selected instructions before rendering.
        // Keep reference to original for range computation (stripping corrupts endDate values).
        Performance originalPerformance = performance;
        if (Boolean.TRUE.equals(isolate) && selectionType == SelectionType.MPM_IDS) {
            performance = Isolation.stripNonSelected(mpmFile, originalPerformance, keepIds);
        }

        ModifyParams params = new ModifyParams();
        if (exaggerate != null) {
            params.exaggerate = new Exaggerate(exaggerate);
            params.exaggerate.applyWeights(getDefaultWeights());
        }

        if (!Boolean.TRUE.equals(isolate) && selectionType == SelectionType.MPM_IDS && params.exaggerate != null) {
            params.exaggerate.scale(
                Shader.bringOut(performance, keepIds, 0.1)
            );
        }

        if (sketchiness != null && sketchiness > 1.0) {
            params.increase = new Increase();
            params.increase.tempo = sketchiness;
            params.increase.dynamics = Math.min(1.0, 1.0 / sketchiness);
            params.increase.imprecision = sketchiness;
        }

        if (params.increase != null || params.exaggerate != null) {
            ModifyService.modify(performance, params);
        }

        Msm expressiveMsm = performance.perform(msm);

        if (!keepIds.isEmpty()) {
            double[] range = new double[] {};
            if (selectionType == SelectionType.NOTE_IDS) {
                range = Isolation.isolateNotes(expressiveMsm, keepIds);
            }
            else if (selectionType == SelectionType.MPM_IDS) {
                range = Isolation.isolateInstructions(originalPerformance, keepIds);
            }
            else if (selectionType == SelectionType.MEASURES) {
                range = Isolation.isolateMeasures(mei, msm, keepIds);
            }
            else if (selectionType == SelectionType.RANGE) {
                Iterator<String> it = keepIds.iterator();
                double from = Double.parseDouble(it.next());
                double to = Double.parseDouble(it.next());
                range = new double[] { from, to };
            }

            this.collectActiveNotes(expressiveMsm, range[0], range[1]);
            if (Boolean.TRUE.equals(exemplify) && selectionType == SelectionType.MPM_IDS) {
                if ((range[1] - range[0]) > MAX_EXEMPLIFY_DURATION) {
                    range[1] = range[0] + MAX_EXEMPLIFY_DURATION;
                }
            }
            if (Boolean.TRUE.equals(context)) {
                range = Isolation.contextualize(range, CONTEXT_AMOUNT, performance);
            }

            this.filterNotesByDate(expressiveMsm, range[0], range[1]);
            shiftOnsetsToFirstNote(expressiveMsm);
        }

        // Yamaha Disklavier expects MIDI channel 0 and ignores other channels.
        for (Element part = expressiveMsm.getRootElement().getFirstChildElement("part"); part != null; part = Helper.getNextSiblingElement("part", part)) {
            Attribute existing = part.getAttribute("midi.channel");
            if (existing != null) {
                part.removeAttribute(existing);
            }
            part.addAttribute(new Attribute("midi.channel", "0"));
        }

        expressiveMsm.removeAllElements("section");
        return expressiveMsm.exportExpressiveMidi();
    }

    private void collectActiveNotes(Msm msm, double minDate, double maxDate) {
        Element root = msm.getRootElement();

        Nodes noteNodes = root.query("descendant::note[@date]");
        for (int i = 0; i < noteNodes.size(); i++) {
            Element note = (Element) noteNodes.get(i);
            String dateStr = note.getAttributeValue("date");
            if (dateStr == null) continue;
            try {
                double d = Double.parseDouble(dateStr);
                if (d >= minDate && d < maxDate) {
                    this.noteIDs.add(note.getAttribute("id", XML_NS).getValue());
                }
            } catch (Exception ignore) {
            }
        }
    }

    private void filterNotesByDate(Msm msm, double minDate, double maxDate) {
        Element root = msm.getRootElement();

        Nodes datedNodes = root.query("descendant::*[@date]");
        List<Element> toRemoveByDate = new ArrayList<>();
        for (int i = 0; i < datedNodes.size(); i++) {
            Element el = (Element) datedNodes.get(i);
            if (el.getLocalName().equals("section")) continue; // skip sections here

            String dateStr = el.getAttributeValue("date");
            if (dateStr == null) continue;
            try {
                double d = Double.parseDouble(dateStr);
                if (d >= maxDate || d < minDate) {
                    toRemoveByDate.add(el);
                }
            } catch (Exception ignore) {
            }
        }
        for (Element e : toRemoveByDate) {
            Element parent = (Element) e.getParent();
            if (parent != null) parent.removeChild(e);
        }

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
            if (msStr != null) {
                double t = Double.parseDouble(msStr);
                double shifted = Math.max(0.0, t - minMs);
                note.addAttribute(new nu.xom.Attribute("milliseconds.date", Double.toString(shifted)));
            }

            String msEndStr = note.getAttributeValue("milliseconds.date.end");
            if (msEndStr != null) {
                double tEnd = Double.parseDouble(msEndStr);
                double shiftedEnd = Math.max(0.0, tEnd - minMs);
                note.addAttribute(new nu.xom.Attribute("milliseconds.date.end", Double.toString(shiftedEnd)));
            }
        }
    }
}
