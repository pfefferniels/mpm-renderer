package meicotools.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import meico.mpm.Mpm;
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
import meico.msm.Msm;
import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Nodes;

public class Isolation {
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
    

    public static double[] isolateInstructions(
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
            double avgTempo = getAverageTempo(tempoMap);

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

            double avgDynamics = getAverageDynamics(dynamicsMap);

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
                String xmlId = el.getAttributeValue("id", XML_NS);
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
}
