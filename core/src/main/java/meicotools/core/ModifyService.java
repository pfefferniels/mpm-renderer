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
import meico.mpm.elements.maps.ImprecisionMap;
import meico.mpm.elements.maps.MetricalAccentuationMap;
import meico.mpm.elements.maps.OrnamentationMap;
import meico.mpm.elements.maps.TempoMap;
import meico.mpm.elements.maps.RubatoMap;
import meico.mpm.elements.maps.data.TempoData;
import meico.mpm.elements.maps.data.DistributionData;
import meico.mpm.elements.maps.data.DynamicsData;
import meico.mpm.elements.maps.data.RubatoData;
import meico.mpm.elements.styles.GenericStyle;
import meico.mpm.elements.styles.OrnamentationStyle;
import meico.mpm.elements.styles.defs.OrnamentDef;
import meico.mpm.elements.styles.defs.OrnamentDef.TemporalSpread;

import nu.xom.Element;
import nu.xom.Attribute;

public class ModifyService {
  private static final double MIN_BPM = 10.0;
  private static final double MAX_BPM = 400.0;
  private static final double MIN_VELOCITY = 1.0;
  private static final double MAX_VELOCITY = 127.0;
  private static final double MIN_RUBATO_INTENSITY = 0.1;
  private static final double MAX_RUBATO_INTENSITY = 5.0;
  private static final double MIN_FRAME_LENGTH = 1.0;
  private static final double MAX_FRAME_LENGTH = 2000.0;
  private static final double MIN_SCALE_FACTOR = 0.01;
  private static final double MAX_SCALE_FACTOR = 50.0;

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
  public static class ModifyParams {
    public Increase increase;
    public Exaggerate exaggerate;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Increase {
    public Double tempo;
    public Double dynamics;
    public Double imprecision;
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

  public static void humanize(Performance perf, double imprecisionMs) throws Exception {
    ImprecisionMap timingImprecision = ImprecisionMap.createImprecisionMap("timing");
    timingImprecision.addDistributionCompensatingTriangle(0.0, 4.0, -imprecisionMs, imprecisionMs, -imprecisionMs, imprecisionMs, 300.0);
    perf.getGlobal().getDated().addMap(timingImprecision);
  }

  public static void modify(Performance perf, ModifyParams params, double imprecisionMs) throws Exception {
    humanize(perf, imprecisionMs);

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
      if (params.increase.imprecision != null) {
        forEachMap(perf, Mpm.IMPRECISION_MAP_TIMING, map -> {
          scaleTimingImprecision((ImprecisionMap) map, params.increase.imprecision);
        });
      }
    }

    if (params.exaggerate != null) {
      if (params.exaggerate.tempo != null) {
        forEachMap(perf, Mpm.TEMPO_MAP, map -> {
          exaggerateTempo((TempoMap) map, params.exaggerate.tempo);
        });
      }

      if (params.exaggerate.dynamics != null) {
        forEachMap(perf, Mpm.DYNAMICS_MAP, map -> {
          exaggerateDynamics((DynamicsMap) map, params.exaggerate.dynamics);
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

  public static void scaleTimingImprecision(ImprecisionMap imprecisionMap, double f) {
    if (imprecisionMap.getDomain() == null
        || (!imprecisionMap.getDomain().equals("timing")
            && !imprecisionMap.getDomain().equals("toneduration"))
        ) {
      return;
    }
    for (int i = 0; i < imprecisionMap.size(); i++) {
      DistributionData dd = imprecisionMap.getDistributionDataOf(i);
      if (dd == null) continue;

      Element el = imprecisionMap.getElement(i);
      el.addAttribute(new Attribute("limit.lower", Double.toString(dd.lowerLimit * f)));
      el.addAttribute(new Attribute("limit.upper", Double.toString(dd.upperLimit * f)));

      el.addAttribute(new Attribute("clip.upper", Double.toString(dd.upperClip * f)));
      el.addAttribute(new Attribute("clip.lower", Double.toString(dd.lowerClip * f)));
    }
  }

  // Apply log-space scaling around geometric mean for dynamics transitions
  public static void exaggerateDynamics(DynamicsMap dynamicsMap, double scale) {
    for (int i = 0; i < dynamicsMap.size(); i++) {
      DynamicsData dd = dynamicsMap.getDynamicsDataOf(i);
      if (dd == null) continue;

      if (dd.volume == null || dd.transitionTo == null) continue;
      if (dd.volume <= 0 || dd.transitionTo <= 0) continue;

      double logVol = Math.log(dd.volume);
      double logTo = Math.log(dd.transitionTo);
      double logMean = (logVol + logTo) / 2.0;
      double newVolume = clamp(Math.exp(logMean + (logVol - logMean) * scale), MIN_VELOCITY, MAX_VELOCITY);
      double newTo = clamp(Math.exp(logMean + (logTo - logMean) * scale), MIN_VELOCITY, MAX_VELOCITY);

      Element el = dynamicsMap.getElement(i);
      el.addAttribute(new Attribute("volume", Double.toString(newVolume)));
      el.addAttribute(new Attribute("transition.to", Double.toString(newTo)));
    }
  }

  // Apply log-space scaling around geometric mean for tempo transitions
  public static void exaggerateTempo(TempoMap tempoMap, double scale) {
    for (int i = 0; i < tempoMap.size(); i++) {
      TempoData td = tempoMap.getTempoDataOf(i);
      if (td == null) continue;
      if (td.bpm == null || td.transitionTo == null) continue;
      if (td.bpm <= 0 || td.transitionTo <= 0) continue;

      double logBpm = Math.log(td.bpm);
      double logTo = Math.log(td.transitionTo);
      double logMean = (logBpm + logTo) / 2.0;
      double newBpm = clamp(Math.exp(logMean + (logBpm - logMean) * scale), MIN_BPM, MAX_BPM);
      double newTo = clamp(Math.exp(logMean + (logTo - logMean) * scale), MIN_BPM, MAX_BPM);

      Element el = tempoMap.getElement(i);
      el.addAttribute(new Attribute("bpm", Double.toString(newBpm)));
      el.addAttribute(new Attribute("transition.to", Double.toString(newTo)));

      if (td.meanTempoAt != null) {
        double newMeanTempoAt = (td.meanTempoAt - 0.5) * scale + 0.5;
        newMeanTempoAt = Math.max(0.1, Math.min(0.9, newMeanTempoAt));
        el.addAttribute(new Attribute("meanTempoAt", Double.toString(newMeanTempoAt)));
      }
    }
  }

  // Apply power-based scaling for rubato intensity
  public static void exaggerateRubatoIntensity(RubatoMap rubatoMap, double scale) {
    for (int i = 0; i < rubatoMap.size(); i++) {
      RubatoData rd = rubatoMap.getRubatoDataOf(i);
      if (rd == null || rd.intensity == null) continue;
      if (rd.intensity <= 0) continue;

      double newIntensity = clamp(Math.pow(rd.intensity, scale), MIN_RUBATO_INTENSITY, MAX_RUBATO_INTENSITY);
      Element el = rubatoMap.getElement(i);
      el.addAttribute(new Attribute("intensity", Double.toString(newIntensity)));
    }
  }

  // Log-space scaling of @frameLength around geometric mean of all frame lengths
  public static void exaggerateTemporalSpread(OrnamentationStyle style, double scale) {
    HashMap<String, OrnamentDef> defs = style.getAllDefs();

    // Collect valid frame lengths to compute geometric mean
    List<Double> lengths = new ArrayList<>();
    for (OrnamentDef def : defs.values()) {
      TemporalSpread ts = def.getTemporalSpread();
      if (ts != null && ts.getFrameLength() > 0) {
        lengths.add(ts.getFrameLength());
      }
    }
    if (lengths.isEmpty()) return;

    double logSum = 0;
    for (double len : lengths) {
      logSum += Math.log(len);
    }
    double logMean = logSum / lengths.size();

    for (OrnamentDef def : defs.values()) {
      TemporalSpread ts = def.getTemporalSpread();
      if (ts != null && ts.getFrameLength() > 0) {
        double logLen = Math.log(ts.getFrameLength());
        double newLen = clamp(Math.exp(logMean + (logLen - logMean) * scale), MIN_FRAME_LENGTH, MAX_FRAME_LENGTH);
        ts.setFrameLength(newLen);
      }
    }
  }

  public static void exaggerateDynamicsGradient(OrnamentationMap ornamentationMap, double f) {
    for (int i = 0; i < ornamentationMap.size(); i++) {
      Element el = ornamentationMap.getElement(i);
      Attribute scale = el.getAttribute("scale");
      if (scale == null) continue;

      double oldScale = Double.parseDouble(scale.getValue());
      el.addAttribute(new Attribute("scale", Double.toString(clamp(oldScale * f, MIN_SCALE_FACTOR, MAX_SCALE_FACTOR))));
    }
  }

  public static void exaggerateAccentuation(MetricalAccentuationMap accentuationMap, double f) {
    for (int i = 0; i < accentuationMap.size(); i++) {
      Element el = accentuationMap.getElement(i);
      Attribute scale = el.getAttribute("scale");
      if (scale == null) continue;

      double oldScale = Double.parseDouble(scale.getValue());
      el.addAttribute(new Attribute("scale", Double.toString(clamp(oldScale * f, MIN_SCALE_FACTOR, MAX_SCALE_FACTOR))));
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
