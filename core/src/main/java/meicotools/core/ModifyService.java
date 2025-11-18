package meicotools.core;


import java.util.*;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.mpm.elements.Part;
import meico.mpm.elements.Global;
import meico.mpm.elements.Dated;
import meico.mpm.elements.Header;
import meico.mpm.elements.maps.DynamicsMap;
import meico.mpm.elements.maps.GenericMap;
import meico.mpm.elements.maps.MetricalAccentuationMap;
import meico.mpm.elements.maps.OrnamentationMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.data.TempoData;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.styles.GenericStyle;
import meico.mpm.elements.styles.OrnamentationStyle;
import meico.mpm.elements.styles.defs.OrnamentDef;
import meico.mpm.elements.styles.defs.OrnamentDef.TemporalSpread;

import nu.xom.Element;
import nu.xom.Attribute;

public class ModifyService {
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
    public Double rubato = 1.0;
    public Double tempo = 1.0;
    public Double dynamics = 1.0;
    public Double temporalSpread = 1.0;
    public Double dynamicsGradient = 1.0;
    public Double relativeVelocity = 1.0;
    public Double relativeDuration = 1.0;
    public Double accentuation = 1.0;

    Exaggerate() {}

    Exaggerate(Double defaultValue) {
      this.rubato = defaultValue;
      this.tempo = defaultValue;
      this.dynamics = defaultValue;
      this.temporalSpread = defaultValue;
      this.dynamicsGradient = defaultValue;
      this.relativeVelocity = defaultValue;
      this.relativeDuration = defaultValue;
      this.accentuation = defaultValue;
    }

    public void applyWeights(Exaggerate weights) {
      this.rubato = (this.rubato - 1.0) * weights.rubato + 1.0;
      this.tempo = (this.tempo - 1.0) * weights.tempo + 1.0;
      this.dynamics = (this.dynamics - 1.0) * weights.dynamics + 1.0;
      this.temporalSpread = (this.temporalSpread - 1.0) * weights.temporalSpread + 1.0;
      this.dynamicsGradient = (this.dynamicsGradient - 1.0) * weights.dynamicsGradient + 1.0;
      this.relativeVelocity = (this.relativeVelocity - 1.0) * weights.relativeVelocity + 1.0;
      this.relativeDuration = (this.relativeDuration - 1.0) * weights.relativeDuration + 1.0;
      this.accentuation = (this.accentuation - 1.0) * weights.accentuation + 1.0;
    }

    public void scale(Exaggerate scale) {
      this.rubato *= scale.rubato;
      this.tempo *= scale.tempo;
      this.dynamics *= scale.dynamics;
      this.temporalSpread *= scale.temporalSpread;
      this.dynamicsGradient *= scale.dynamicsGradient;
      this.relativeVelocity *= scale.relativeVelocity;
      this.relativeDuration *= scale.relativeDuration;
      this.accentuation *= scale.accentuation;
    }
  }

  public static void modify(Performance perf, ModifyParams params) throws Exception {
    if (params.increase != null) {
      if (params.increase.tempo != null) {
        forEachMap(perf, Mpm.TEMPO_MAP, map -> {
          scaleTempo((TempoMap) map, params.increase.tempo);
        });
      }
      if (params.increase.dynamics != null) {
        forEachMap(perf, Mpm.DYNAMICS_MAP, map -> {
          scaleDynamics((DynamicsMap) map, params.increase.dynamics);
        });
      }
    }

    if (params.exaggerate != null) {
      if (params.exaggerate.tempo != null) {
        forEachMap(perf, Mpm.TEMPO_MAP, map -> {
          exaggarateTempo((TempoMap) map, params.exaggerate.tempo);
        });
      }

      if (params.exaggerate.dynamics != null) {
        forEachMap(perf, Mpm.DYNAMICS_MAP, map -> {
          exaggarateDynamics((DynamicsMap) map, params.exaggerate.dynamics);
        });
      }

      if (params.exaggerate.temporalSpread != null) {
        forEachStyle(perf, Mpm.ORNAMENTATION_STYLE, style -> {
          exaggerateTemporalSpread((OrnamentationStyle) style, params.exaggerate.temporalSpread);
        });
      }

      if (params.exaggerate.rubato != null) {
        forEachMap(perf, Mpm.RUBATO_MAP, map -> {
          exaggerateRubatoIntensity((RubatoMap) map, params.exaggerate.rubato);
        });
      }

      if (params.exaggerate.dynamicsGradient != null) {
        forEachMap(perf, Mpm.ORNAMENTATION_MAP, map -> {
          exaggerateDynamicsGradient((OrnamentationMap) map, params.exaggerate.dynamicsGradient);
        });
      }

      if (params.exaggerate.accentuation != null) {
        forEachMap(perf, Mpm.METRICAL_ACCENTUATION_MAP, map -> {
          exaggerateAccentuation((MetricalAccentuationMap) map, params.exaggerate.accentuation);
        });
      }
    }
  }

  public static void scaleTempo(TempoMap tempoMap, double f) {
    for (int i = 0; i <  tempoMap.size(); i++) {
      TempoData td = tempoMap.getTempoDataOf(i);
      if (td == null || td.bpm == null) continue;

      double newBpm = td.bpm * f;
      Element el = tempoMap.getElement(i);
      el.addAttribute(new Attribute("bpm", Double.toString(newBpm)));

      if (td.transitionTo != null) {
        double newTo = td.transitionTo * f;
        el.addAttribute(new Attribute("transition.to", Double.toString(newTo)));
      }
    }
  }

  public static void scaleDynamics(DynamicsMap dynamicsMap, double f) {
    for (int i = 0; i < dynamicsMap.size(); i++) {
      DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
      if (dd == null || dd.volume == null) continue;

      double volume = dd.volume * f;
      Element el = dynamicsMap.getElement(i);
      el.addAttribute(new Attribute("volume", Double.toString(volume)));

      if (dd.transitionTo != null) {
        double newTo = dd.transitionTo * f;
        el.addAttribute(new Attribute("transition.to", Double.toString(newTo)));
      }
    }
  }

  // Apply scaling around mean for tempo transitions
  public static void exaggarateDynamics(DynamicsMap dynamicsMap, double scale) {
    for (int i = 0; i < dynamicsMap.size(); i++) {
      DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
      if (dd == null) continue;

      // We want entries that have both volume and transitionTo (i.e., a transition segment)
      if (dd.volume == null || dd.transitionTo == null) continue;

      double mean = (dd.volume + dd.transitionTo) / 2.0;
      double newVolume = mean + (dd.volume - mean) * scale;
      double newTo = mean + (dd.transitionTo - mean) * scale;

      Element el = dynamicsMap.getElement(i);
      el.addAttribute(new Attribute("volume", Double.toString(newVolume)));
      el.addAttribute(new Attribute("transition.to", Double.toString(newTo)));
    }
  }

  // Apply scaling around mean for tempo transitions
  public static void exaggarateTempo(TempoMap tempoMap, double scale) {
    for (int i = 0; i < tempoMap.size(); i++) {
      TempoData td = tempoMap.getTempoDataOf(i);
      if (td == null) continue;
      if (td.bpm == null || td.transitionTo == null) continue;

      double mean = (td.bpm + td.transitionTo) / 2.0;
      double newBpm = mean + (td.bpm - mean) * scale;
      double newTo = mean + (td.transitionTo - mean) * scale;

      Element el = tempoMap.getElement(i);
      el.addAttribute(new Attribute("bpm", Double.toString(newBpm)));
      el.addAttribute(new Attribute("transition.to", Double.toString(newTo)));

      if (td.meanTempoAt != null) {
        double newMeanTempoAt = (td.meanTempoAt - 0.5) * scale + 0.5;
        el.addAttribute(new Attribute("meanTempoAt", Double.toString(newMeanTempoAt)));
      }
    }
  }

  // Apply (x - 1) * scale + 1 for rubato intensity where present.
  public static void exaggerateRubatoIntensity(RubatoMap rubatoMap, double scale) {
    for (int i = 0; i < rubatoMap.size(); i++) {
      RubatoData rd = rubatoMap.getRubatoDataOf(i);
      if (rd == null || rd.intensity == null) continue;

      double newIntensity = (rd.intensity - 1.0) * scale + 1.0;
      Element el = rubatoMap.getElement(i);
      el.addAttribute(new Attribute("intensity", Double.toString(newIntensity)));      
    }
  }

  // Multiply @frameLength by scale
  public static void exaggerateTemporalSpread(OrnamentationStyle style, double scale) {
    HashMap<String, OrnamentDef> defs = style.getAllDefs();
    for (OrnamentDef def : defs.values()) {
      TemporalSpread ts = def.getTemporalSpread();
      if (ts != null) {
        double oldLen = ts.getFrameLength();
        ts.setFrameLength(oldLen * scale);
      }
    }
  }

  public static void exaggerateDynamicsGradient(OrnamentationMap ornamentationMap, double f) {
    for (int i = 0; i < ornamentationMap.size(); i++) {
      Element el = ornamentationMap.getElement(i);
      Attribute scale = el.getAttribute("scale");
      if (scale == null) continue;

      double oldScale = Double.parseDouble(scale.getValue());
      el.addAttribute(new Attribute("scale", Double.toString(oldScale * f)));
    }
  }

  public static void exaggerateAccentuation(MetricalAccentuationMap accentuationMap, double f) {
    for (int i = 0; i < accentuationMap.size(); i++) {
      Element el = accentuationMap.getElement(i);
      Attribute scale = el.getAttribute("scale");
      if (scale == null) continue;

      double oldScale = Double.parseDouble(scale.getValue());
      el.addAttribute(new Attribute("scale", Double.toString(oldScale * f)));
    }
  }

  private static <T extends GenericMap> void forEachMap(Performance perf, String type, Consumer<T> visitor) {
    ArrayList<Dated> dateds = new ArrayList<>();
    Global g = perf.getGlobal();
    if (g != null && g.getDated() != null) dateds.add(g.getDated());

    for (Part p : perf.getAllParts()) {
      if (p != null && p.getDated() != null) dateds.add(p.getDated());
    }

    for (Dated dated : dateds) {
      GenericMap map = dated.getMap(type);
      if (map != null) {
        visitor.accept((T) map);
      }
    }
  }

  private static <T extends GenericStyle<?>> void forEachStyle(Performance perf, String type, Consumer<T> visitor) {
    ArrayList<T> styles = new ArrayList<>();
    Global g = perf.getGlobal();
    if (g != null && g.getHeader() != null) {
      Header header = g.getHeader();
      Map<String, GenericStyle> map = header.getAllStyleDefs(type);
      for (GenericStyle style : map.values()) {
        styles.add((T) style);
      }
    }

    for (Part p : perf.getAllParts()) {
      if (p != null && p.getHeader() != null) {
        Header pHeader = p.getHeader();
        Map<String, GenericStyle> pMap = pHeader.getAllStyleDefs(Mpm.ORNAMENTATION_STYLE);
        for (GenericStyle style : pMap.values()) {
          styles.add((T) style);
        }
      }
    }

    for (T style : styles) {
      visitor.accept(style);
    }
  }
}
