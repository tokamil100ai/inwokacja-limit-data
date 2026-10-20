package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Punktacja dróg względem pozycji i kierunku jazdy (bez pamięci; pamięć jest w {@link RoadTracker}).
 *
 * Koszt drogi to pitagorejska suma trzech składników, wszystkie w "metrach równoważnych":
 *   koszt = sqrt(odległość² + kąt² + klasa²)
 * - odległość: do najbliższego odcinka drogi,
 * - kąt: różnica między kursem GPS a kierunkiem odcinka, gładko od zera (sin), z uwzględnieniem jednokierunkowości;
 *   kąt prosty kosztuje {@link #ANGLE_M}, jazda pod prąd po drodze jednokierunkowej trochę więcej,
 * - klasa: droga niższej klasy (serwisowa, osiedlowa) przegrywa z główną; im gorsza dokładność GPS, tym ta kara jest większa,
 *   bo wtedy sama pozycja mówi mniej.
 * Droga odpada, gdy jest dalej niż próg zależny od dokładności GPS albo jej koszt przekracza {@link #GATE_M}.
 */
public final class RoadMatcher {
  public static final class Match {
    public final Road road;
    public final double distM;
    public final int limit;
    public final Road.Source source;
    /** Koszt wybranej drogi (metry równoważne), do diagnostyki. */
    public final double cost;

    Match(Road road, double distM, int limit, Road.Source source, double cost) {
      this.road = road; this.distM = distM; this.limit = limit; this.source = source; this.cost = cost;
    }
  }

  /** Droga z policzonym kosztem i kierunkami najbliższego odcinka. */
  public static final class Cand {
    public final Road road;
    public final double distM;
    public final double cost;
    /** Kierunek najbliższego odcinka (stopnie od północy, zgodnie z kolejnością punktów). */
    public final double segBearing;
    /** Kierunek cięciwy wokół najbliższego odcinka (stabilniejszy na łukach), do rozstrzygania "zgodnie / pod prąd". */
    public final double chordBearing;

    Cand(Road road, double distM, double cost, double segBearing, double chordBearing) {
      this.road = road; this.distM = distM; this.cost = cost; this.segBearing = segBearing; this.chordBearing = chordBearing;
    }
  }

  /** Koszt kąta prostego względem drogi, w metrach równoważnych. */
  public static final double ANGLE_M = 45;
  /** Największa możliwa kara za jazdę pod prąd po drodze jednokierunkowej (20% więcej niż kąt prosty). */
  static final double WRONG_WAY_FACTOR = 1.2;
  /**
   * Kara za klasę drogi: od {@link #CLASS_MIN_M} do {@link #CLASS_MAX_M}, proporcjonalnie do dokładności GPS. Wartości dobrane symulacją
   * ({@code Sim}): większe pomagają na głównej obok serwisowej, ale szkodzą na osiedlowej obok głównej; to wybrany kompromis.
   * Warto je przestroić na prawdziwych nagraniach jazdy.
   */
  static final double CLASS_PER_ACC = 0.6, CLASS_MIN_M = 4, CLASS_MAX_M = 10;
  /** Powyżej tego kosztu droga odpada. */
  public static final double GATE_M = 55;
  /** Premia dla drogi, na której już byliśmy (histereza przy równoległych ulicach). */
  public static final double PREV_BONUS_M = 7;

  private static final double M_PER_DEG_LAT = 110540.0;
  private static final double M_PER_DEG_LON_EQ = 111320.0;
  private static final double DEFAULT_ACCURACY_M = 25;

  /** Dopasowanie bez pamięci. Zwraca null, gdy żadna droga nie pasuje. prevId to droga dopasowana poprzednio (lub -1). */
  public static Match match(List<Road> roads, double lat, double lon, double bearingDeg,
                            double accuracyM, long prevId) {
    List<Cand> ranked = rank(roads, lat, lon, bearingDeg, accuracyM);
    if (ranked.isEmpty()) return null;
    Cand best = null;
    double bestScore = Double.MAX_VALUE;
    for (Cand c : ranked) {
      double s = c.cost - (c.road.id == prevId ? PREV_BONUS_M : 0);
      if (s < bestScore) { bestScore = s; best = c; }
    }
    return resolve(best, bearingDeg);
  }

  /** Drogi, które przeszły próg, od najlepszej (najniższy koszt). Pusta lista, gdy nic nie pasuje. */
  public static List<Cand> rank(List<Road> roads, double lat, double lon, double bearingDeg, double accuracyM) {
    List<Cand> out = new ArrayList<Cand>();
    if (roads == null || roads.isEmpty()) return out;
    if (Double.isNaN(lat) || Double.isNaN(lon)) return out;
    double acc = Double.isNaN(accuracyM) || accuracyM < 0 ? DEFAULT_ACCURACY_M : accuracyM;
    double maxD = clamp(acc * 1.6, 22, 45);
    double classMax = clamp(acc * CLASS_PER_ACC, CLASS_MIN_M, CLASS_MAX_M);
    double kLon = Math.cos(Math.toRadians(lat)) * M_PER_DEG_LON_EQ;

    for (Road r : roads) {
      if (r == null || r.points() < 2) continue;
      double[] nd = nearest(r, lat, lon, kLon);
      double d = nd[0];
      if (d > maxD) continue;
      double a = Double.isNaN(bearingDeg) ? 0 : angleCost(bearingDeg, nd[1], r.oneway);
      double c = classMax * (1.0 - importance(r.highway) / 5.0);
      double cost = Math.sqrt(d * d + a * a + c * c);
      if (cost > GATE_M) continue;
      out.add(new Cand(r, d, cost, nd[1], nd[2]));
    }
    Collections.sort(out, new Comparator<Cand>() {
      @Override public int compare(Cand x, Cand y) { return Double.compare(x.cost, y.cost); }
    });
    return out;
  }

  /** Kara kąta w metrach równoważnych. bearing i segBearing w stopniach, oneway jak w {@link Road#oneway}. */
  static double angleCost(double bearingDeg, double segBearingDeg, int oneway) {
    double delta = angDiff(bearingDeg, segBearingDeg);          // 0..180
    if (oneway == 0) {
      double d = Math.min(delta, 180 - delta);                  // dwukierunkowa: obie strony równie dobre
      return ANGLE_M * Math.sin(Math.toRadians(d));
    }
    double d = oneway == 1 ? delta : 180 - delta;               // kąt względem dozwolonego kierunku, 0..180
    if (d <= 90) return ANGLE_M * Math.sin(Math.toRadians(d));
    return ANGLE_M * (1.0 + (WRONG_WAY_FACTOR - 1.0) * (d - 90) / 90.0);
  }

  /** Ważność drogi 0..5 na podstawie typu z OSM (5 = autostrada i droga ekspresowa, 0 = serwisowa i podobne). */
  static int importance(String highway) {
    if (highway == null) return 1;
    String h = highway.endsWith("_link") ? highway.substring(0, highway.length() - 5) : highway;
    switch (h) {
      case "motorway": case "trunk": return 5;
      case "primary": return 4;
      case "secondary": return 3;
      case "tertiary": return 2;
      case "unclassified": case "residential": case "road": case "": return 1;
      default: return 0;     // service, living_street, track, pedestrian...
    }
  }

  /** Z wybranej drogi robi wynik: wybiera limit zależnie od kierunku jazdy. */
  static Match resolve(Cand c, double bearingDeg) {
    Road best = c.road;
    int limit = best.limit;
    Road.Source src = best.source;
    if (best.limitFwd != Maxspeed.UNKNOWN || best.limitBwd != Maxspeed.UNKNOWN) {
      boolean fwd;
      if (best.oneway == 1) fwd = true;
      else if (best.oneway == -1) fwd = false;
      else if (!Double.isNaN(bearingDeg) && !Double.isNaN(c.chordBearing)) fwd = angDiff(bearingDeg, c.chordBearing) < 90;
      else fwd = true;
      int dirLimit = fwd ? best.limitFwd : best.limitBwd;
      if (dirLimit != Maxspeed.UNKNOWN) { limit = dirLimit; src = Road.Source.SIGN; }
      else if (limit == Maxspeed.UNKNOWN) {
        // jest tylko limit przeciwnego kierunku: nie wiemy, jaki obowiązuje tu
        src = Road.Source.NONE;
      }
    }
    return new Match(best, c.distM, limit, src, c.cost);
  }

  /** Czy droga ma znany limit (jawny, szacunek albo kierunkowy). */
  static boolean hasLimit(Road r) {
    return r.limit != Maxspeed.UNKNOWN || r.limitFwd != Maxspeed.UNKNOWN || r.limitBwd != Maxspeed.UNKNOWN;
  }

  /**
   * Zwraca {odległość w metrach, kierunek najbliższego odcinka, kierunek cięciwy} (stopnie od północy, zgodnie z kolejnością punktów).
   * Cięciwa biegnie od początku najbliższego odcinka do punktu po jego końcu, więc na łuku i na wierzchołku jest stabilniejsza.
   */
  static double[] nearest(Road r, double lat, double lon, double kLon) {
    double bestD2 = Double.MAX_VALUE, bestB = 0;
    int bestEnd = -1;
    int n = r.points();
    double px = 0, py = 0;   // punkt odniesienia w początku układu
    double prevX = (r.lon[0] - lon) * kLon, prevY = (r.lat[0] - lat) * M_PER_DEG_LAT;
    for (int i = 1; i < n; i++) {
      double x = (r.lon[i] - lon) * kLon, y = (r.lat[i] - lat) * M_PER_DEG_LAT;
      double dx = x - prevX, dy = y - prevY;
      double len2 = dx * dx + dy * dy;
      double t = len2 < 1e-9 ? 0 : clamp(((px - prevX) * dx + (py - prevY) * dy) / len2, 0, 1);
      double cx = prevX + t * dx, cy = prevY + t * dy;
      double d2 = cx * cx + cy * cy;
      if (d2 < bestD2 && len2 >= 1e-9) {
        bestD2 = d2;
        bestB = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0;
        bestEnd = i;
      }
      prevX = x; prevY = y;
    }
    if (bestD2 == Double.MAX_VALUE) bestD2 = 1e12;   // wszystkie odcinki zerowej długości
    double chord = bestB;
    if (bestEnd >= 0) {
      int from = Math.max(0, bestEnd - 1), to = Math.min(n - 1, bestEnd + 1);
      double dx = (r.lon[to] - r.lon[from]) * kLon, dy = (r.lat[to] - r.lat[from]) * M_PER_DEG_LAT;
      if (dx * dx + dy * dy >= 1e-9) chord = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0;
    }
    return new double[] { Math.sqrt(bestD2), bestB, chord };
  }

  static double angDiff(double a, double b) {
    double d = Math.abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0);
    return d;
  }

  static double clamp(double v, double lo, double hi) { return Math.min(hi, Math.max(lo, v)); }
}
