package meicotools.core;

// params.json shape (missing keys are allowed):
// {
//   "increase": { "tempo": 0.5, "dynamics": 0.1 },
//   "exaggerate": {
//     "rubato": 0.4,
//     "tempo": 0.2,
//     "dynamics": 0.0,
//     "temporalSpread": 0.2,
//     "dynamicsGradient": 0.1,
//     "relativeVelocity": 0.3,
//     "relativeDuration": 0.25
//   }
// }

import java.util.*;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.mpm.elements.Part;
import meico.mpm.elements.Global;
import meico.mpm.elements.Dated;
import meico.mpm.elements.Header;
import meico.mpm.elements.maps.GenericMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.data.TempoData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.styles.GenericStyle;
import meico.mpm.elements.styles.defs.OrnamentDef;
import meico.mpm.elements.styles.defs.OrnamentDef.TemporalSpread;

// nu.xom for attribute edits where needed
import nu.xom.Element;
import nu.xom.Attribute;

public class ModifyService {

  // ---------- JSON model ----------
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ModifyParams {
    public Increase increase;
    public Exaggerate exaggerate;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Increase {
    public Double tempo;
    public Double dynamics;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Exaggerate {
    public Double rubato;
    public Double tempo;
    public Double dynamics;
    public Double temporalSpread;
    public Double dynamicsGradient;
    public Double relativeVelocity;
    public Double relativeDuration;
  }

  public static void modify(Mpm mpm, ModifyParams params) throws Exception {
    if (params.increase != null) {
      if (params.increase.tempo != null) {
        applyTempoScale(mpm, params.increase.tempo);
      }
      if (params.increase.dynamics != null) {
        // TODO
      }
    }
    if (params.exaggerate != null) {
      if (params.exaggerate.tempo != null) {
        applyTempoScale(mpm, params.exaggerate.tempo);
      }
      if (params.exaggerate.rubato != null) {
        applyRubatoIntensityScale(mpm, params.exaggerate.rubato);
      }
      if (params.exaggerate.temporalSpread != null) {
        applyOrnamentTemporalSpreadScale(mpm, params.exaggerate.temporalSpread);
      }
      // TODO
      // dynamics, dynamicsGradient, relativeVelocity, relativeDuration
    }
  }

  // Apply (f + 1) scaling around mean for tempo transitions with bpm & transitionTo present.
  private static void applyTempoScale(Mpm mpm, double f) {
    forEachDated(mpm, dated -> {
      GenericMap map = dated.getMap(Mpm.TEMPO_MAP); // TempoMap expected
      if (!(map instanceof TempoMap)) return;
      TempoMap tempoMap = (TempoMap) map;
      int n = map.size();
      for (int i = 0; i < n; i++) {
        TempoData td = tempoMap.getTempoDataOf(i);
        System.out.println("TempoData " + i + ": " + td.startDate);
        if (td == null) continue;

        // We want entries that have both bpm and transitionTo (i.e., a transition segment)
        if (td.bpm == null || td.transitionTo == null) continue;

        double mean = (td.bpm + td.transitionTo) / 2.0;
        double scale = f + 1.0;
        double newBpm = mean + (td.bpm - mean) * scale;
        double newTo  = mean + (td.transitionTo - mean) * scale;

        // Update XML attributes (TempoData exposes xml Element)
        if (td.xml != null) {
          setNumericAttr(td.xml, "bpm", newBpm);
          setNumericAttr(td.xml, "transition.to", newTo);
          // Keep string mirrors if present
          td.bpm = newBpm;
          td.bpmString = Double.toString(newBpm);
          td.transitionTo = newTo;
          td.transitionToString = Double.toString(newTo);
        }
      }
    });
  }

  // Apply (x - 1) * (f + 1) + 1 for rubato intensity where present.
  private static void applyRubatoIntensityScale(Mpm mpm, double f) {
    forEachDated(mpm, dated -> {
      GenericMap map = dated.getMap(Mpm.RUBATO_MAP);
      if (!(map instanceof RubatoMap)) return;
      RubatoMap rubatoMap = (RubatoMap) map;
      int n = map.size();
      for (int i = 0; i < n; i++) {
        RubatoData rd = rubatoMap.getRubatoDataOf(i);
        if (rd == null || rd.intensity == null) continue;
        double newIntensity = (rd.intensity - 1.0) * (f + 1.0) + 1.0;

        if (rd.xml != null) {
          setNumericAttr(rd.xml, "intensity", newIntensity);
          rd.intensity = newIntensity;
        }
      }
    });
  }

  // Multiply OrnamentDef.TemporalSpread.frameLength by (f + 1) in both Global and Part headers.
  private static void applyOrnamentTemporalSpreadScale(Mpm mpm, double f) {
    double scale = f + 1.0;

    // Global header
    forEachHeader(mpm, header -> {
      Map<String, GenericStyle> styles = header.getAllStyleDefs(Mpm.ORNAMENTATION_STYLE);

      for (GenericStyle style : styles.values()) {
        HashMap<String, OrnamentDef> defs = style.getAllDefs();
        for (OrnamentDef def : defs.values()) {
          TemporalSpread ts = def.getTemporalSpread();
          if (ts != null) {
            double oldLen = ts.getFrameLength();
            ts.setFrameLength(oldLen * scale);
          }
        }
      }
    });
  }

  // ---------- Traversal ----------
  private static void forEachDated(Mpm mpm, Consumer<Dated> visitor) {
    // global dated
    for (int i = 0; i < mpm.size(); i++) {
      Performance perf = mpm.getPerformance(i);
      if (perf == null) continue;

      Global g = perf.getGlobal();
      if (g != null && g.getDated() != null) visitor.accept(g.getDated());

      for (Part p : perf.getAllParts()) {
        if (p != null && p.getDated() != null) visitor.accept(p.getDated());
      }
    }
  }

  private static void forEachHeader(Mpm mpm, Consumer<Header> visitor) {
    for (int i = 0; i < mpm.size(); i++) {
      Performance perf = mpm.getPerformance(i);
      if (perf == null) continue;

      Global g = perf.getGlobal();
      if (g != null && g.getHeader() != null) visitor.accept(g.getHeader());

      for (Part p : perf.getAllParts()) {
        if (p != null && p.getHeader() != null) visitor.accept(p.getHeader());
      }
    }
  }

  // ---------- XML helpers ----------
  private static void setNumericAttr(Element xml, String name, double value) {
    Attribute a = xml.getAttribute(name);
    if (a == null) {
      xml.addAttribute(new Attribute(name, Double.toString(value)));
    } else {
      a.setValue(Double.toString(value));
    }
  }
}
