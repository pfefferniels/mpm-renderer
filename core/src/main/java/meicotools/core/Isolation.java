package meicotools.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import meico.mei.Mei;
import meico.mpm.Mpm;
import meico.mpm.elements.Dated;
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
import net.sf.saxon.expr.Component.M;
import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Node;
import nu.xom.Nodes;
import nu.xom.XPathContext;

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
                    if (dd.position == 0 && dd.transitionTo == null) {
                        // end of movement: consider only the start date
                        if (dd.startDate > maxDate) {
                            maxDate = dd.startDate;
                        }
                    }
                    else {
                        if (dd.endDate > maxDate) {
                            maxDate = dd.endDate;
                        }
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

    public static double[] pickExample(Performance performance, Set<String> keepIds) {
        ArrayList<Double> relevantDates = new ArrayList<Double>();
        Nodes nodes = performance.getXml().query("descendant::*[@date]");
        if (nodes.size() == 0) {
            System.out.println("No dated elements in performance");
            return new double[] { 0.0, 0.0 };
        }

        for (Node node : nodes) {
            Element el = (Element) node;
            String dateStr = el.getAttributeValue("date");
            String xmlId = el.getAttributeValue("id", "http://www.w3.org/XML/1998/namespace");
            if (xmlId == null || !keepIds.contains(xmlId)) {
                continue;
            }

            try {
                Double d = Double.parseDouble(dateStr);
                relevantDates.add(d);
            } catch (Exception ignore) {}
        }

        if (relevantDates.isEmpty()) {
            System.out.println("This should not happen: no relevant dates found for the provided MPM IDs.");
            return new double[] {0.0, 0.0};
        }

        Collections.sort(relevantDates);

        if (relevantDates.size() == 1) {
            double date = relevantDates.get(0);
            return new double[] {date, date + 1};
        }

        double lastDate = relevantDates.get(relevantDates.size() - 1);
        double firstDate = relevantDates.get(0);

        double distance = lastDate - firstDate;

        return new double[] { firstDate, Math.min(firstDate + distance, firstDate + 5760.0)  };

        /*

        // Find the best cluster with at least 2 dates (smallest range)
        double bestStart = relevantDates.get(0);
        double bestEnd = relevantDates.get(1);
        double bestRange = bestEnd - bestStart;

        for (int i = 0; i < relevantDates.size() - 1; i++) {
            for (int j = i + 1; j < relevantDates.size(); j++) {
                double currentRange = relevantDates.get(j) - relevantDates.get(i);
                if (currentRange < bestRange) {
                    bestRange = currentRange;
                    bestStart = relevantDates.get(i);
                    bestEnd = relevantDates.get(j);
                }
            }
        }

        return new double[] { bestStart, bestEnd };*/
    }

    public static double[] contextualize(double[] range, double context, Performance perf) {
        double contextInTicks = context * 4 * 720;
        double start = Math.max(range[0] - contextInTicks, 0);
        double end = range[1] + (contextInTicks / 2.0);

        // Make sure, that the bits around should be played somewhat quieter.
        Dated d = perf.getGlobal().getDated();
        DynamicsMap map = (DynamicsMap) d.getMap(Mpm.DYNAMICS_MAP);

        DynamicsData startData = map.getDynamicsDataAt(range[0]);
        double startTarget = startData.getDynamicsAt(range[0]);

        DynamicsData endData = map.getDynamicsDataAt(range[1]);
        double endTarget = endData.getDynamicsAt(range[1]);

        if (map == null) {
            d.addMap(DynamicsMap.createDynamicsMap());
        }
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
                throw new Error("Invalid measure specification: " + measureInfo);
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

        System.out.println("measures" + measures + " resolved to fromMeasure=" + fromMeasure + " " + toMeasure);

        double[] fromDates = getDatesForMeasure(mei, msm, fromMeasure, fromRepeat);
        double[] toDates = getDatesForMeasure(mei, msm, toMeasure, toRepeat);

        System.out.println("Isolated measures " + fromMeasure + (fromRepeat ? "-rpt" : "") + " to " + toMeasure + (toRepeat ? "-rpt" : "") +
            " corresponding to dates " + fromDates[0] + " to " + toDates[1]);

        return new double[] { fromDates[0], toDates[1] };
    }

    private static double[] getDatesForMeasure(Mei mei, Msm msm, int measure, boolean inRepeat) {
        XPathContext context = new XPathContext();
        context.addNamespace("mei", "http://www.music-encoding.org/ns/mei");
        context.addNamespace("xml", "http://www.w3.org/XML/1998/namespace");
        Nodes nodes = mei.getRootElement().query("descendant::mei:measure[@n='" + measure + "']", context);
        System.out.println("Found " + nodes.size() + " measure elements with n='" + measure + "' (inRepeat=" + inRepeat + ")");
        if (nodes.size() == 0) {
            throw new Error("No measure with n='" + measure + "' found in MEI.");
        }

        // Element nodes;
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
            String xmlId = note.getAttributeValue("id", "http://www.w3.org/XML/1998/namespace");
            if (xmlId == null) continue;

            System.out.println("Test:" + msm.getRootElement().query("descendant::*[@xml:id]", context).size());

            Nodes msmNotes = msm.getRootElement().query("descendant::*[@xml:id='" + xmlId + "']", context);
            if (msmNotes.size() == 0) {
                continue;
            }

            String date = ((Element) msmNotes.get(0)).getAttributeValue("date");
            System.out.println("Note xml:id='" + xmlId + "' has date='" + date + "'");
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

        System.out.println("Measure " + measure + (inRepeat ? "-rpt" : "") + " has date range: " + minDate + " to " + maxDate);

        return new double[] { minDate, maxDate };
    }
}
