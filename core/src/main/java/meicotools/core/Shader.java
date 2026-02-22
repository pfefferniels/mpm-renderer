package meicotools.core;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import meico.mpm.elements.Performance;
import meicotools.core.ModifyService.Exaggerate;
import nu.xom.Element;
import nu.xom.Nodes;

public class Shader {

    private static final Map<String, List<String>> TYPE_TO_FIELDS = Map.of(
        "tempo",               List.of("tempo"),
        "dynamics",            List.of("dynamics"),
        "rubato",              List.of("rubato"),
        "accentuationPattern", List.of("accentuation"),
        "ornament",            List.of("temporalSpread", "dynamicsGradient"),
        "articulation",        List.of("relativeVelocity", "relativeDuration")
    );

    private static final Map<String, BiConsumer<Exaggerate, Double>> FIELD_SETTERS = Map.of(
        "rubato",            (e, v) -> e.rubato = v,
        "tempo",             (e, v) -> e.tempo = v,
        "dynamics",          (e, v) -> e.dynamics = v,
        "temporalSpread",    (e, v) -> e.temporalSpread = v,
        "dynamicsGradient",  (e, v) -> e.dynamicsGradient = v,
        "relativeVelocity",  (e, v) -> e.relativeVelocity = v,
        "relativeDuration",  (e, v) -> e.relativeDuration = v,
        "accentuation",      (e, v) -> e.accentuation = v
    );

    public static Exaggerate bringOut(Performance perf, Set<String> mpmIDs, double factor) {
        Exaggerate exaggerate = new Exaggerate();
        if (mpmIDs.isEmpty()) {
            return exaggerate;
        }

        Set<String> fieldsToReduce = new HashSet<>(FIELD_SETTERS.keySet());

        Element xml = perf.getXml();
        for (String id : mpmIDs) {
            Nodes node = xml.query("//*[@xml:id='" + id + "']");
            if (node == null || node.size() == 0) {
                continue;
            }
            String type = ((Element) node.get(0)).getLocalName();
            List<String> fields = TYPE_TO_FIELDS.get(type);
            if (fields != null) {
                fieldsToReduce.removeAll(fields);
            }
        }

        for (String field : fieldsToReduce) {
            FIELD_SETTERS.get(field).accept(exaggerate, factor);
        }

        return exaggerate;
    }
}
