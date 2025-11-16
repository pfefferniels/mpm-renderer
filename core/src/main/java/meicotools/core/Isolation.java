package meicotools.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.MetricalAccentuationMap;
import meico.mpm.elements.maps.MovementMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.mpm.elements.maps.data.MetricalAccentuationData;
import meico.mpm.elements.maps.data.MovementData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.maps.data.TempoData;
import meico.msm.Msm;
import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Nodes;

public class Isolation {
    public static double[] isolateInstructions(
        Performance performance,
        Set<String> mpmIDs
    ) {
        System.out.println("Isolating MPM elements by xml:id: " + mpmIDs);

        // Find all dated elements and store those whose xml:id is in mpmIDs
        final String XML_NS = "http://www.w3.org/XML/1998/namespace";
        Nodes candidates = performance.getXml().query("descendant::*[@date]");
        List<Element> selectedElements = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Element el = (Element) candidates.get(i);
            String xmlId = el.getAttributeValue("id", XML_NS);
            if (xmlId != null && mpmIDs.contains(xmlId)) {
                selectedElements.add(el);
            }
        }

        System.out.println("Found " + selectedElements.size() + " matching elements: " +
            selectedElements
                .stream()
                .map(e -> e.getAttributeValue("id", XML_NS))
                .collect(Collectors.toList())
        );

        if (selectedElements.isEmpty()) {
            throw new IllegalArgumentException("No matching MPM elements found for the provided xml:id set.");
        }

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
        maxDate += 1;
        System.out.println("Starting with minDate=" + minDate + " maxDate=" + maxDate);

        // 1) Deal with <tempo>
        {
            TempoMap tempoMap = (TempoMap) performance.getGlobal().getDated().getMap(Mpm.TEMPO_MAP);
            for (int i=0; i<tempoMap.size(); i++) {
                TempoData td = tempoMap.getTempoDataOf(i);
                if (mpmIDs.contains(td.xmlId)) {
                    if (td.endDate > maxDate) {
                        System.out.println("(tempo) Adjusting maxDate from " + maxDate + " to " + td.endDate);
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

        // 3) Deal with <movement>
        {
            MovementMap movementMap = (MovementMap) performance.getGlobal().getDated().getMap(Mpm.MOVEMENT_MAP);
            for (int i=0; i<movementMap.size(); i++) {
                MovementData dd = movementMap.getMovementDataOf(i);
                if (mpmIDs.contains(dd.xmlId)) {
                    if (dd.endDate > maxDate) {
                        maxDate = dd.endDate;
                    }
                }
            }
        }

        // 4) Deal <rubato>
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

        // 5) Deal with <metricalAccentuation>
        {
            MetricalAccentuationMap metricalAccentuationMap = (MetricalAccentuationMap) performance.getGlobal().getDated().getMap(Mpm.METRICAL_ACCENTUATION_MAP);
            for (int i=0; i<metricalAccentuationMap.size(); i++) {
                MetricalAccentuationData md = metricalAccentuationMap.getMetricalAccentuationDataOf(i);
                if (md == null) continue;

                if (mpmIDs.contains(md.xmlId)) {
                    double length = (md.accentuationPatternDef.getLength() * 720 * 4) / 4.0; // 4.0 = denominator
                    if (md.startDate + length > maxDate) {
                        maxDate = md.startDate + length;
                    }
                }
            }
        }

        System.out.println("Final range after isolating MPM ids: minDate=" + minDate + " maxDate=" + maxDate);

        return new double[] {minDate, maxDate};
    }

    public static double[] isolateNotes(
        Msm msm,
        Set<String> keepIds
    ) {
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

    private static final double MIN_RANGE_DISTANCE = 3600;
    private static final double SUBRANGE_SIZE = 2880;
    private static final double CONTEXT_SIZE = 1440;
    
    public static double[] pick(double[] range) {
        double min = range[0];
        double max = range[1];
        
        // Ensure the given range has minimum distance of 1440
        if (max - min < MIN_RANGE_DISTANCE) {
            System.out.println("Given range is too small. Expanding to minimum distance of " + MIN_RANGE_DISTANCE);
            return range;
        } 
        
        // Calculate maximum start position to ensure we can fit a 720 range
        double maxStart = max - SUBRANGE_SIZE;
        
        // Pick random start within valid bounds
        double start = min + Math.random() * (maxStart - min);
        double end = start + SUBRANGE_SIZE;
        
        return new double[]{start, end};
    }

    public static double[] contextualize(double[] range) {
        double start = Math.max(range[0] - CONTEXT_SIZE, 0);
        double end = range[1] + CONTEXT_SIZE;
        return new double[]{ start, end };
    }
}
