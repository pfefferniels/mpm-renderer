package meicotools.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import meico.mei.Mei;
import meico.mpm.Mpm;
import meico.mpm.elements.Dated;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.GenericMap;
import meico.mpm.elements.maps.MetricalAccentuationMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.mpm.elements.maps.data.MetricalAccentuationData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.maps.data.TempoData;
import meico.msm.Msm;
import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Node;
import nu.xom.Nodes;
import nu.xom.XPathContext;

public class Isolation {
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final double MAX_EXEMPLIFY_DURATION = 5760.0;
    private static final int DEFAULT_PPQ = 720;

    public static double computeWeightedAverageTempo(TempoMap tempoMap) {
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        double lastDuration = 1.0;

        for (int i = 0; i < tempoMap.size(); i++) {
            TempoData td = tempoMap.getTempoDataOf(i);
            if (td == null || td.bpm == null) continue;

            double duration;
            if (i + 1 < tempoMap.size()) {
                TempoData next = tempoMap.getTempoDataOf(i + 1);
                duration = next.startDate - td.startDate;
            } else {
                // Last element: reuse previous segment's duration
                // (endDate is often Double.MAX_VALUE as a sentinel)
                duration = lastDuration;
            }

            if (duration <= 0) continue;
            lastDuration = duration;
            weightedSum += td.bpm * duration;
            totalWeight += duration;
        }

        if (totalWeight == 0.0) return 100.0;
        return weightedSum / totalWeight;
    }

    public static double computeWeightedAverageDynamics(DynamicsMap dynamicsMap) {
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        double lastDuration = 1.0;

        for (int i = 0; i < dynamicsMap.size(); i++) {
            DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
            if (dd == null || dd.volume == null) continue;

            double duration;
            if (i + 1 < dynamicsMap.size()) {
                DynamicsData next = dynamicsMap.getDynamicsDataOf(i + 1);
                duration = next.startDate - dd.startDate;
            } else {
                duration = lastDuration;
            }

            if (duration <= 0) continue;
            lastDuration = duration;
            weightedSum += dd.volume * duration;
            totalWeight += duration;
        }

        if (totalWeight == 0.0) return 70.0;
        return weightedSum / totalWeight;
    }

    public static Performance stripNonSelected(File mpmFile, Performance originalPerformance, Set<String> keepIds) throws Exception {

        // Compute averages from original before stripping
        TempoMap originalTempoMap = (TempoMap) originalPerformance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        DynamicsMap originalDynamicsMap = (DynamicsMap) originalPerformance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);

        double avgTempo = computeWeightedAverageTempo(originalTempoMap);
        double avgDynamics = computeWeightedAverageDynamics(originalDynamicsMap);

        // Reload a fresh copy to avoid mutating the original
        Mpm clonedMpm = new Mpm(mpmFile);
        Performance clonedPerf = clonedMpm.getPerformance(0);
        Dated dated = clonedPerf.getGlobal().getDated();

        // Strip non-selected elements from each map type
        String[] mapTypes = {
            Mpm.TEMPO_MAP, Mpm.DYNAMICS_MAP, Mpm.RUBATO_MAP,
            Mpm.METRICAL_ACCENTUATION_MAP, Mpm.ORNAMENTATION_MAP,
            Mpm.ARTICULATION_MAP, Mpm.MOVEMENT_MAP
        };

        for (String mapType : mapTypes) {
            GenericMap map = dated.getMap(mapType);
            if (map == null) continue;

            for (int i = map.size() - 1; i >= 0; i--) {
                Element el = map.getElement(i);
                // Preserve <style> switch elements — they set the active style
                // definition and are needed for style resolution in maps like
                // metricalAccentuationMap and ornamentationMap.
                if ("style".equals(el.getLocalName())) continue;

                String xmlId = el.getAttributeValue("id", XML_NS);
                if (xmlId == null || !keepIds.contains(xmlId)) {
                    map.removeElement(i);
                }
            }
        }

        // Always insert neutral defaults at date 0 for required maps.
        // Even if a selected element exists (e.g. dynamics_1440), notes before it
        // need a baseline — otherwise meico defaults to velocity 100.
        TempoMap tempoMap = (TempoMap) dated.getMap(Mpm.TEMPO_MAP);
        if (tempoMap != null) {
            boolean hasTempoAtZero = tempoMap.size() > 0
                && tempoMap.getTempoDataOf(0) != null
                && tempoMap.getTempoDataOf(0).startDate == 0.0;
            if (!hasTempoAtZero) {
                tempoMap.addTempo(0.0, Double.toString(avgTempo), 0.25);
            }
        }

        DynamicsMap dynamicsMap = (DynamicsMap) dated.getMap(Mpm.DYNAMICS_MAP);
        if (dynamicsMap != null) {
            boolean hasDynamicsAtZero = dynamicsMap.size() > 0
                && dynamicsMap.getDynamicsDataOf(0) != null
                && dynamicsMap.getDynamicsDataOf(0).startDate == 0.0;
            if (!hasDynamicsAtZero) {
                dynamicsMap.addDynamics(0.0, Double.toString(avgDynamics));
            }
        }

        // Restore endDate bounds for transition instructions whose successors were
        // stripped.  meico computes endDate from the next element in the map — when
        // the successor is removed, endDate becomes Double.MAX_VALUE, stretching the
        // transition curve over an infinite range and flattening it.  Fix: insert a
        // constant-value cap at the original endDate so meico recomputes correctly.
        capTransitionEndDates(originalPerformance, dated, keepIds);

        return clonedPerf;
    }

    private static void capTransitionEndDates(Performance originalPerformance, Dated strippedDated, Set<String> keepIds) {
        // Tempo transitions
        TempoMap origTempoMap = (TempoMap) originalPerformance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
        TempoMap strippedTempoMap = (TempoMap) strippedDated.getMap(Mpm.TEMPO_MAP);
        if (origTempoMap != null && strippedTempoMap != null) {
            for (int i = 0; i < origTempoMap.size(); i++) {
                TempoData origTd = origTempoMap.getTempoDataOf(i);
                if (origTd == null || origTd.xmlId == null) continue;
                if (!keepIds.contains(origTd.xmlId)) continue;
                if (origTd.transitionTo == null) continue;

                double origEndDate = origTd.endDate;
                if (origEndDate <= origTd.startDate || origEndDate >= Double.MAX_VALUE / 2) continue;

                boolean hasCap = false;
                for (int j = 0; j < strippedTempoMap.size(); j++) {
                    TempoData std = strippedTempoMap.getTempoDataOf(j);
                    if (std != null && std.startDate >= origEndDate - 0.5) {
                        hasCap = true;
                        break;
                    }
                }

                if (!hasCap) {
                    strippedTempoMap.addTempo(origEndDate, Double.toString(origTd.transitionTo), origTd.beatLength);
                }
            }
        }

        // Dynamics transitions
        DynamicsMap origDynMap = (DynamicsMap) originalPerformance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);
        DynamicsMap strippedDynMap = (DynamicsMap) strippedDated.getMap(Mpm.DYNAMICS_MAP);
        if (origDynMap != null && strippedDynMap != null) {
            for (int i = 0; i < origDynMap.size(); i++) {
                DynamicsData origDd = origDynMap.getDynamicsDataOf(i);
                if (origDd == null || origDd.xmlId == null) continue;
                if (!keepIds.contains(origDd.xmlId)) continue;
                if (origDd.transitionTo == null) continue;

                double origEndDate = origDd.endDate;
                if (origEndDate <= origDd.startDate || origEndDate >= Double.MAX_VALUE / 2) continue;

                boolean hasCap = false;
                for (int j = 0; j < strippedDynMap.size(); j++) {
                    DynamicsData sdd = strippedDynMap.getDynamicsDataOf(j);
                    if (sdd != null && sdd.startDate >= origEndDate - 0.5) {
                        hasCap = true;
                        break;
                    }
                }

                if (!hasCap) {
                    strippedDynMap.addDynamics(origEndDate, Double.toString(origDd.transitionTo));
                }
            }
        }
    }

    public static double[] isolateInstructions(
        Performance performance,
        Set<String> mpmIDs
    ) {
        // Find all dated elements and classify selected ones.
        // Movement elements (sustain pedal, pitch bend) produce MIDI CC events
        // but should not drive the note selection range.
        Nodes candidates = performance.getXml().query("descendant::*[@date]");
        List<Element> noteDrivingElements = new ArrayList<>();
        boolean hasAnyMatch = false;

        for (int i = 0; i < candidates.size(); i++) {
            Element el = (Element) candidates.get(i);
            String xmlId = el.getAttributeValue("id", XML_NS);
            if (xmlId != null && mpmIDs.contains(xmlId)) {
                hasAnyMatch = true;
                if (!"movement".equals(el.getLocalName())) {
                    noteDrivingElements.add(el);
                }
            }
        }

        if (!hasAnyMatch) {
            throw new IllegalArgumentException("No matching MPM elements found for the provided xml:id set.");
        }

        // Movements-only selection: no notes, just pedal MIDI events in silence
        if (noteDrivingElements.isEmpty()) {
            return new double[] { 0.0, 0.0 };
        }

        double minDate = Double.POSITIVE_INFINITY;
        double maxDate = 0.0;

        for (Element el : noteDrivingElements) {
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
        maxDate += 1;

        // Extend maxDate for instruction types whose effect spans beyond their start date.
        // Movement elements are intentionally excluded — they produce MIDI CC events
        // but should not cause additional notes to appear in the rendering.

        // 1) Deal with <tempo>
        {
            TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
            for (int i=0; i<tempoMap.size(); i++) {
                TempoData td = tempoMap.getTempoDataOf(i);
                if (mpmIDs.contains(td.xmlId)) {
                    if (td.endDate > maxDate) {
                        maxDate = td.endDate;
                    }
                }
            }
        }

        // 2) Deal with <dynamics>
        {
            DynamicsMap dynamicsMap = (DynamicsMap) performance.getGlobal().getDated().getMap(Mpm.DYNAMICS_MAP);

            for (int i=0; i<dynamicsMap.size(); i++) {
                DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
                if (mpmIDs.contains(dd.xmlId)) {
                    if (dd.endDate > maxDate) {
                        maxDate = dd.endDate;
                    }
                }
            }
        }

        // 3) Deal with <rubato>
        {
            RubatoMap rubatoMap = (RubatoMap) performance.getGlobal().getDated().getMap(Mpm.RUBATO_MAP);
            for (int i=0; i<rubatoMap.size(); i++) {
                RubatoData rd = rubatoMap.getRubatoDataOf(i);
                if (mpmIDs.contains(rd.xmlId)) {
                    if (rd.startDate + rd.frameLength > maxDate) {
                        maxDate = rd.startDate + rd.frameLength;
                    }
                }
            }
        }

        // 4) Deal with <metricalAccentuation>
        {
            MetricalAccentuationMap metricalAccentuationMap = (MetricalAccentuationMap) performance.getGlobal().getDated().getMap(Mpm.METRICAL_ACCENTUATION_MAP);
            for (int i=0; i<metricalAccentuationMap.size(); i++) {
                MetricalAccentuationData md = metricalAccentuationMap.getMetricalAccentuationDataOf(i);
                if (md == null) continue;

                if (mpmIDs.contains(md.xmlId)) {
                    double length = md.accentuationPatternDef.getLength() * DEFAULT_PPQ;
                    if (md.startDate + length > maxDate) {
                        maxDate = md.startDate + length;
                    }
                }
            }
        }

        return new double[] {minDate, maxDate};
    }

    public static double[] isolateNotes(
        Msm msm,
        Set<String> keepIds
    ) {
        Element root = msm.getRootElement();
        Nodes noteNodes = root.query("descendant::note");
        List<Element> toRemove = new ArrayList<>();
        List<Double> dates = new ArrayList<>();
        for (int i = 0; i < noteNodes.size(); i++) {
            Element note = (Element) noteNodes.get(i);
            Attribute xmlId = note.getAttribute("id", XML_NS);
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

    public static double[] pickExample(Performance performance, Set<String> keepIds) {
        ArrayList<Double> relevantDates = new ArrayList<Double>();
        Nodes nodes = performance.getXml().query("descendant::*[@date]");
        if (nodes.size() == 0) {
            return new double[] { 0.0, 0.0 };
        }

        for (Node node : nodes) {
            Element el = (Element) node;
            String dateStr = el.getAttributeValue("date");
            String xmlId = el.getAttributeValue("id", XML_NS);
            if (xmlId == null || !keepIds.contains(xmlId)) {
                continue;
            }

            try {
                double d = Double.parseDouble(dateStr);
                relevantDates.add(d);
            } catch (Exception ignore) {}
        }

        if (relevantDates.isEmpty()) {
            return new double[] {0.0, 0.0};
        }

        Collections.sort(relevantDates);

        if (relevantDates.size() == 1) {
            double date = relevantDates.get(0);
            return new double[] {date, date + 1};
        }

        double firstDate = relevantDates.get(0);
        double lastDate = relevantDates.get(relevantDates.size() - 1);
        double distance = lastDate - firstDate;

        return new double[] { firstDate, Math.min(firstDate + distance, firstDate + MAX_EXEMPLIFY_DURATION) };
    }

    public static double[] contextualize(double[] range, double context, Performance perf) {
        double contextInTicks = context * 4 * DEFAULT_PPQ;
        double start = Math.max(range[0] - contextInTicks, 0);
        double end = range[1] + (contextInTicks / 2.0);

        // Make the context around the selection quieter.
        Dated d = perf.getGlobal().getDated();
        DynamicsMap map = (DynamicsMap) d.getMap(Mpm.DYNAMICS_MAP);
        if (map == null) {
            map = DynamicsMap.createDynamicsMap();
            d.addMap(map);
        }

        DynamicsData startData = map.getDynamicsDataAt(range[0]);
        double startTarget = startData.getDynamicsAt(range[0]);

        DynamicsData endData = map.getDynamicsDataAt(range[1]);
        double endTarget = endData.getDynamicsAt(range[1]);

        map.addDynamics(start, "30.0", Double.toString(startTarget), 0.4, 0.0);

        for (int i = 0; i < map.size(); i++) {
            DynamicsData dd = map.getDynamicsDataOf(i);
            if (dd.startDate >= range[1] && dd.startDate <= endTarget) {
                map.removeElement(i);
                i--;
            }
        }

        map.addDynamics(range[1], Double.toString(endTarget + 1), "30.0", 0.4, 0.0);
        map.addDynamics(endTarget + 1, "30.0");

        return new double[]{ start, end };
    }

    public static double[] isolateMeasures(
        Mei mei,
        Msm msm,
        Set<String> measures
    ) {
        boolean fromRepeat = false;
        int fromMeasure = Integer.MAX_VALUE;
        boolean toRepeat = false;
        int toMeasure = 0;

        for (String measureInfo : measures) {
            boolean withinRepeat = false;
            if (measureInfo.endsWith("-rpt")) {
                measureInfo = measureInfo.substring(0, measureInfo.length() - 4);
                fromRepeat = true;
            }
            String[] parts = measureInfo.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid measure specification: " + measureInfo);
            }

            int m = Integer.parseInt(parts[0]);
            if (m < fromMeasure) {
                fromMeasure = m;
                fromRepeat = withinRepeat;
            }
            if (m > toMeasure) {
                toMeasure = m;
                toRepeat = withinRepeat;
            }
        }

        fromMeasure += 1;
        toMeasure += 1;

        double[] fromDates = getDatesForMeasure(mei, msm, fromMeasure, fromRepeat);
        double[] toDates = getDatesForMeasure(mei, msm, toMeasure, toRepeat);

        return new double[] { fromDates[0], toDates[1] };
    }

    private static double[] getDatesForMeasure(Mei mei, Msm msm, int measure, boolean inRepeat) {
        XPathContext context = new XPathContext();
        context.addNamespace("mei", "http://www.music-encoding.org/ns/mei");
        context.addNamespace("xml", XML_NS);
        Nodes nodes = mei.getRootElement().query("descendant::mei:measure[@n='" + measure + "']", context);
        if (nodes.size() == 0) {
            throw new IllegalArgumentException("No measure with n='" + measure + "' found in MEI.");
        }

        Element measureEl = (Element) nodes.get(0);
        if (inRepeat && nodes.size() == 2) {
            // pick the one which is in the repeat
            measureEl = (Element) nodes.get(1);
        }

        Nodes notes = measureEl.query("descendant::mei:note", context);
        double minDate = Double.POSITIVE_INFINITY;
        double maxDate = 0.0;
        for (int i = 0; i < notes.size(); i++) {
            Element note = (Element) notes.get(i);
            String xmlId = note.getAttributeValue("id", XML_NS);
            if (xmlId == null) continue;

            Nodes msmNotes = msm.getRootElement().query("descendant::*[@xml:id='" + xmlId + "']", context);
            if (msmNotes.size() == 0) {
                continue;
            }

            String date = ((Element) msmNotes.get(0)).getAttributeValue("date");
            try {
                double d = Double.parseDouble(date);
                if (d < minDate) {
                    minDate = d;
                }
                if (d > maxDate) {
                    maxDate = d;
                }
            } catch (Exception ignore) {}
        }

        return new double[] { minDate, maxDate };
    }
}
