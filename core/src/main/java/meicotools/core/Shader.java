package meicotools.core;

import java.util.HashSet;
import java.util.Set;

import meico.mpm.elements.Performance;
import meicotools.core.ModifyService.Exaggerate;
import nu.xom.Element;
import nu.xom.Nodes;

public class Shader {
    public static Exaggerate bringOut(Performance perf, Set<String> mpmIDs, double factor) {
        Exaggerate exaggerate = new Exaggerate();
        if (mpmIDs.size() == 0) {
            return exaggerate;
        }

        Set<String> fieldsToReduce = new HashSet<>(Set.of(
            "rubato",
            "tempo",
            "dynamics",
            "temporalSpread",
            "dynamicsGradient",
            "relativeVelocity",
            "relativeDuration",
            "accentuation"
        ));

        Element xml = perf.getXml();
        for (String id : mpmIDs) {
            Nodes node = xml.query("//*[@xml:id='" + id + "']");
            if (node == null || node.size() == 0) {
                System.out.println("No element found with xml:id: " + id);
                continue;
            }
            Element el = (Element) node.get(0);
            String type = el.getLocalName();
            System.out.println("Processing element with xml:id: " + id + ", type: " + type);

            if (type == "tempo") {
                fieldsToReduce.remove("tempo");
            }
            else if (type == "dynamics") {
                fieldsToReduce.remove("dynamics");
            }
            else if (type == "rubato") {
                fieldsToReduce.remove("rubato");
            }
            else if (type == "accentuationPattern") {
                fieldsToReduce.remove("accentuation");
            }
            else if (type == "ornament") {
                fieldsToReduce.remove("temporalSpread");
                fieldsToReduce.remove("dynamicsGradient");
            }
            else if (type == "articulation") {
                fieldsToReduce.remove("relativeVelocity");
                fieldsToReduce.remove("relativeDuration");
            }
        }

        for (String field : fieldsToReduce) {
            switch (field) {
                case "rubato":
                    exaggerate.rubato = factor;
                    break;
                case "tempo":
                    exaggerate.tempo = factor;
                    break;
                case "dynamics":
                    exaggerate.dynamics = factor;
                    break;
                case "temporalSpread":
                    exaggerate.temporalSpread = factor;
                    break;
                case "dynamicsGradient":
                    exaggerate.dynamicsGradient = factor;
                    break;
                case "relativeVelocity":
                    exaggerate.relativeVelocity = factor;
                    break;
                case "relativeDuration":
                    exaggerate.relativeDuration = factor;
                    break;
                case "accentuation":
                    exaggerate.accentuation = factor;
                    break;
            }
        }

        return exaggerate;
    }
}
