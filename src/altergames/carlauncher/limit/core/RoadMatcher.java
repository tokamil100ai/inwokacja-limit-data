package altergames.carlauncher.limit.core;

import java.util.List;

/**
 * Dopasowanie pozycji i kierunku jazdy do najbliższej drogi z listy.
 * Bierze pod uwagę odległość, zgodność kierunku z odcinkiem (w tym jednokierunkowość) oraz histerezę,
 * żeby przy równoległych drogach wynik nie przeskakiwał.
 */
public final class RoadMatcher {
  public static final class Match {
    public final Road road;
    public final double distM;
    public final int limit;
    public final Road.Source source;

    Match(Road road, double distM, int limit, Road.Source source) {
      this.road = road; this.distM = distM; this.limit = limit; this.source = source;
    }
  }

  private static final double M_PER_DEG_LAT = 110540.0;
  private static final double M_PER_DEG_LON_EQ = 111320.0;

  /** Dopasowanie. Zwraca null, gdy żadna droga nie jest dość blisko. prevId to droga dopasowana poprzednio (lub -1). */
  public static Match match(List<Road> roads, double lat, double lon, double bearingDeg,
                            double accuracyM, long prevId) {
    if (roads == null || roads.isEmpty()) return null;
    if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
    double acc = Double.isNaN(accuracyM) || accuracyM < 0 ? 25 : accuracyM;
    double maxD = clamp(acc * 1.6, 22, 45);
    double kLon = Math.cos(Math.toRadians(lat)) * M_PER_DEG_LON_EQ;

    Road best = null;
    double bestScore = Double.MAX_VALUE, bestDist = 0, bestSegBearing = Double.NaN;
    for (Road r : roads) {
      if (r == null || r.points() < 2) continue;
      double[] nd = nearest(r, lat, lon, kLon);
      double d = nd[0];
      if (d > maxD) continue;
      double score = d;
      if (!Double.isNaN(bearingDeg)) {
        double delta = angDiff(bearingDeg, nd[1]);              // 0..180
        double dirDelta;
        if (r.oneway == 1) dirDelta = delta;
        else if (r.oneway == -1) dirDelta = 180 - delta;
        else dirDelta = Math.min(delta, 180 - delta);
        if (dirDelta > 35) score += (dirDelta - 35) * 0.6;
        if (r.oneway != 0 && dirDelta > 100) score += 120;     // jazda pod prąd względem tej drogi: to raczej inna droga
      }
      if (r.id == prevId) score -= 7;                            // histereza
      if (score < bestScore) { bestScore = score; best = r; bestDist = d; bestSegBearing = nd[1]; }
    }
    if (best == null) return null;
    int limit = best.limit;
    Road.Source src = best.source;
    if (best.limitFwd != Maxspeed.UNKNOWN || best.limitBwd != Maxspeed.UNKNOWN) {
      boolean fwd;
      if (best.oneway == 1) fwd = true;
      else if (best.oneway == -1) fwd = false;
      else if (!Double.isNaN(bearingDeg) && !Double.isNaN(bestSegBearing)) fwd = angDiff(bearingDeg, bestSegBearing) < 90;
      else fwd = true;
      int dirLimit = fwd ? best.limitFwd : best.limitBwd;
      if (dirLimit != Maxspeed.UNKNOWN) { limit = dirLimit; src = Road.Source.SIGN; }
      else if (limit == Maxspeed.UNKNOWN) {
        // jest tylko limit przeciwnego kierunku: nie wiemy, jaki obowiązuje tu
        src = Road.Source.NONE;
      }
    }
    return new Match(best, bestDist, limit, src);
  }

  /** Zwraca {odległość w metrach, kierunek odcinka w stopniach od północy zgodnie z kolejnością punktów}. */
  static double[] nearest(Road r, double lat, double lon, double kLon) {
    double bestD2 = Double.MAX_VALUE, bestB = 0;
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
      }
      prevX = x; prevY = y;
    }
    if (bestD2 == Double.MAX_VALUE) bestD2 = 1e12;   // wszystkie odcinki zerowej długości
    return new double[] { Math.sqrt(bestD2), bestB };
  }

  static double angDiff(double a, double b) {
    double d = Math.abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0);
    return d;
  }

  private static double clamp(double v, double lo, double hi) { return Math.min(hi, Math.max(lo, v)); }
}
