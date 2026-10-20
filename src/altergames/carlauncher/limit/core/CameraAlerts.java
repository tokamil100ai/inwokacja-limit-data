package altergames.carlauncher.limit.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostrzeganie o fotoradarach (logika alertu z prototypu: ikona punktu, limit, odległość, strzałka zbliżania, "ZONE" w odcinkowym
 * pomiarze). Z listy urządzeń z okolicy wybiera to, które jest przed autem na jego drodze i w jego kierunku jazdy, liczy odległość
 * (wzdłuż dopasowanej drogi, a gdy radar nie leży na niej, po prostej w stożku do przodu) i prowadzi stan: zbliżanie, minięcie,
 * wejście w odcinkowy pomiar i wyjście z niego, odrzucenie alertu dotknięciem. Czysta Java; nie wydaje żadnego dźwięku.
 */
public final class CameraAlerts {
  /** Źródło urządzeń z okolicy (w aplikacji: [CameraSource] = paczki offline scalone z danymi na żywo). */
  public interface Source {
    List<Camera> near(double lat, double lon, int radiusM);
  }

  /** Rodzaje punktów jak w prototypie (S.radar.type): fotoradar, kontrola odcinkowa, światła, przejazd kolejowy. */
  public static final int TYPE_CAMERA = 100, TYPE_SECTION = 110, TYPE_LIGHT = 120, TYPE_RAIL = 360;

  /** To, co pokazuje panel alertu. */
  public static final class Alert {
    public final Camera camera;
    public final int type;
    /** Odległość w metrach do punktu; przy zone = true nieistotna. */
    public final int distM;
    /** Jesteśmy wewnątrz odcinka kontroli: panel pokazuje napis ZONE zamiast odległości. */
    public final boolean zone;
    /** Limit do pokazania (z urządzenia, a gdy nieznany z drogi) albo -1. */
    public final int limit;
    public Alert(Camera camera, int type, int distM, boolean zone, int limit) {
      this.camera = camera; this.type = type; this.distM = distM; this.zone = zone; this.limit = limit;
    }
  }

  /** Promień szukania urządzeń wokół auta: z zapasem ponad największy zasięg alertu (3000 m * 1,2) i odświeżanie listy co 400 m. */
  static final int SEARCH_M = 4500;
  /** Wyprzedzenie ostrzeżenia: ile sekund jazdy przed punktem pojawia się alert (ustawienie w aplikacji, suwak). */
  public static final int LEAD_MIN_S = 10, LEAD_MAX_S = 120, LEAD_DEFAULT_S = 45;
  static final double RANGE_MIN_M = 250, RANGE_MAX_M = 3000, RANGE_PER_LEAD_S = 40;
  static final double REFRESH_M = 400;
  static final long REFRESH_MS = 60_000, STOP_KEEP_MS = 10_000, PASSED_QUIET_MS = 40_000;
  public static final double MIN_SPEED_KMH = 8;

  private final LimitEngine.Clock clock;
  private final Source source;

  private List<Camera> list;
  private double listLat, listLon;
  private long listAt = Long.MIN_VALUE / 2;
  private long lastUpdateAt;
  private Alert current;
  private Camera prevCam;
  private double prevDist;
  private String shownKey = "", dismissedKey = "";
  private Camera sectionStart;
  private long sectionAt;
  private final Map<String, Long> passed = new HashMap<String, Long>();
  private String lastError = "";
  private int updates, listRefreshes;
  private int leadSec = LEAD_DEFAULT_S;

  public CameraAlerts(LimitEngine.Clock clock, Source source) { this.clock = clock; this.source = source; }

  /** Wyprzedzenie ostrzeżenia w sekundach jazdy (10 do 120); zasięg to prędkość razy ten czas, nie mniej niż 250 m i nie więcej niż min(3000 m, 40 m na sekundę wyprzedzenia). */
  public synchronized void setLeadSeconds(int s) { leadSec = Math.max(LEAD_MIN_S, Math.min(LEAD_MAX_S, s)); }

  public synchronized int leadSeconds() { return leadSec; }

  /** Zasięg alertu w metrach dla prędkości v (m/s) i wyprzedzenia lead (s). */
  static double rangeFor(double v, int lead) {
    return clamp(v * lead, RANGE_MIN_M, Math.min(RANGE_MAX_M, lead * RANGE_PER_LEAD_S));
  }

  /** Stan alertu do pokazania albo null. Alert bez nowych próbek GPS dłużej niż 15 s (np. tunel) wygasa. Można wołać z wątku interfejsu. */
  public synchronized Alert current() {
    if (current != null && clock.mono() - lastUpdateAt > 15_000) { current = null; prevCam = null; shownKey = ""; }
    return current;
  }

  /** Dotknięcie panelu: ukrywa bieżący alert do czasu minięcia tego punktu. */
  public synchronized void dismiss() {
    if (current != null) { dismissedKey = key(current.camera); current = null; }
  }

  public synchronized void reset() {
    current = null; prevCam = null; shownKey = ""; dismissedKey = ""; sectionStart = null; passed.clear(); list = null;
  }

  public synchronized String diagnostics() {
    return "radary: aktualizacje=" + updates + " listy=" + listRefreshes + " lista=" + (list == null ? "brak" : list.size())
        + (sectionStart != null ? " w odcinku" : "") + " ostatni błąd=" + lastError;
  }

  /**
   * Nowa próbka GPS. road: dopasowana droga (może być null), roadLimit: limit pokazywany na znaku albo -1.
   * Zwraca bieżący alert albo null. Nie zgłasza wyjątków.
   */
  public synchronized Alert update(Fix f, Road road, int roadLimit) {
    try {
      updates++;
      long now = clock.mono();
      boolean moving = f != null && f.valid() && !Double.isNaN(f.speedKmh) && f.speedKmh >= MIN_SPEED_KMH && !Double.isNaN(f.bearingDeg);
      if (!moving) {
        if (current != null && now - lastUpdateAt > STOP_KEEP_MS) { current = null; prevCam = null; shownKey = ""; }
        return current;
      }
      lastUpdateAt = now;
      refreshList(f, now);
      if (list == null || list.isEmpty()) { finishPass(null, now, f); current = null; prevCam = null; return null; }
      double v = f.speedKmh / 3.6;
      double range = rangeFor(v, leadSec);
      double heading = f.bearingDeg;
      double[] cum = road == null ? null : cumulative(road);

      Camera best = null, endCam = null;
      double bestD = Double.MAX_VALUE, endD = Double.MAX_VALUE;
      for (Camera c : list) {
        double d = distanceAhead(c, f, heading, road, cum, range * 1.2);
        if (Double.isNaN(d)) continue;
        String k = key(c);
        Long p = passed.get(k);
        if (p != null && now - p < PASSED_QUIET_MS) continue;
        if (c.kind == Camera.SECTION_END) {
          if (sectionStart != null && sectionStart.pair == c.pair && d < endD) { endCam = c; endD = d; }
          continue;                                                // koniec odcinka tylko w swoim odcinku
        }
        if (c.kind == Camera.SECTION_START && sectionStart != null && sectionStart.pair == c.pair) continue;   // już w odcinku
        if (d < bestD) { best = c; bestD = d; }
      }

      Camera cam = null;
      double dist = 0;
      boolean zone = false;
      if (sectionStart != null) {
        if (now - sectionAt > sectionTimeout(sectionStart)) sectionStart = null;
      }
      if (sectionStart != null) {
        if (endCam != null && endD <= range) { cam = endCam; dist = endD; }
        else { cam = sectionStart; zone = true; }
      } else if (best != null) {
        double limit = key(best).equals(shownKey) ? range * 1.15 : range;
        if (bestD <= limit) { cam = best; dist = bestD; }
      }

      finishPass(zone ? null : cam, now, f);
      if (cam == null) { current = null; prevCam = null; shownKey = ""; return null; }
      if (!zone) { prevCam = cam; prevDist = dist; } else { prevCam = null; }
      String k = key(cam);
      shownKey = k;
      if (k.equals(dismissedKey)) { current = null; return null; }
      int limit = cam.limit > 0 ? cam.limit : sectionStart != null && sectionStart.limit > 0 ? sectionStart.limit : roadLimit > 0 ? roadLimit : -1;
      current = new Alert(cam, typeOf(cam), (int) Math.round(dist), zone, limit);
      return current;
    } catch (RuntimeException e) {
      lastError = "update: " + e.getClass().getSimpleName();
      return current;
    }
  }

  /** Jeśli poprzednio zbliżaliśmy się do punktu, a teraz już go nie ma w alercie z małej odległości, to go minęliśmy. */
  private void finishPass(Camera now, long t, Fix f) {
    if (prevCam == null || prevCam == now) return;
    double passAt = Math.max(60, f.speedKmh / 3.6 * 2.5);
    if (now != null && key(now).equals(key(prevCam))) return;
    if (prevDist <= passAt) {
      passed.put(key(prevCam), t);
      if (prevCam.kind == Camera.SECTION_START) { sectionStart = prevCam; sectionAt = t; }
      else if (prevCam.kind == Camera.SECTION_END && sectionStart != null && sectionStart.pair == prevCam.pair) sectionStart = null;
      if (key(prevCam).equals(dismissedKey)) dismissedKey = "";
      if (passed.size() > 64) passed.clear();
    }
  }

  private static long sectionTimeout(Camera start) {
    long len = start.sectionLen > 0 ? start.sectionLen : 0;
    long ms = len > 0 ? (long) (len / 8.0 * 1000) : 20L * 60 * 1000;     // wolniej niż 29 km/h przez cały odcinek się nie jedzie
    return Math.max(3L * 60 * 1000, Math.min(25L * 60 * 1000, ms));
  }

  private void refreshList(Fix f, long now) {
    if (list != null && Geo.dist(listLat, listLon, f.lat, f.lon) <= REFRESH_M && now - listAt <= REFRESH_MS) return;
    try {
      List<Camera> l = source.near(f.lat, f.lon, SEARCH_M);
      list = l;
      listLat = f.lat; listLon = f.lon; listAt = now;
      listRefreshes++;
    } catch (RuntimeException e) {
      lastError = "źródło: " + e.getClass().getSimpleName();
      listAt = now;                         // nie ponawiamy co próbkę
    }
  }

  /** Odległość do urządzenia przed autem albo NaN, gdy go to nie dotyczy (inny kierunek, za plecami, z boku). */
  static double distanceAhead(Camera c, Fix f, double heading, Road road, double[] cum, double maxRange) {
    if (c.hasDirection() && Geo.angDiff(c.dir, heading) > 70) return Double.NaN;
    if (road != null && cum != null) {
      double along = alongRoad(road, cum, f, heading, c);
      if (!Double.isNaN(along)) return along < -10 || along > maxRange ? Double.NaN : Math.max(0, along);
    }
    double d = Geo.dist(f.lat, f.lon, c.lat, c.lon);
    if (d > maxRange) return Double.NaN;
    double b = Geo.bearing(new double[] {f.lat, f.lon}, new double[] {c.lat, c.lon});
    double diff = Geo.angDiff(b, heading);
    if (d <= 60) return diff <= 80 ? d : Double.NaN;
    double lateral = d * Math.sin(Math.toRadians(diff));
    return diff <= 35 && lateral <= Math.max(35, 0.08 * d) ? d : Double.NaN;
  }

  /** Odległość wzdłuż drogi do urządzenia leżącego na niej (do 35 m od osi) w kierunku jazdy; NaN, gdy urządzenie nie leży na tej drodze. */
  static double alongRoad(Road r, double[] cum, Fix f, double heading, Camera c) {
    double[] car = project(r, cum, f.lat, f.lon), cam = project(r, cum, c.lat, c.lon);
    if (car == null || cam == null || car[0] > 45 || cam[0] > 35) return Double.NaN;
    boolean forward = Geo.angDiff(car[2], heading) < 90;
    return (cam[1] - car[1]) * (forward ? 1 : -1);
  }

  /** Rzut punktu na drogę: {odległość od osi, długość drogi do rzutu, azymut odcinka (zgodny z kolejnością punktów)} albo null. */
  static double[] project(Road r, double[] cum, double lat, double lon) {
    if (r.points() < 2) return null;
    double cos = Math.cos(Math.toRadians(lat));
    double bestOff = Double.MAX_VALUE, bestS = 0, bestBearing = 0;
    for (int i = 1; i < r.points(); i++) {
      double ax = 0, ay = 0;
      double bx = (r.lon[i] - r.lon[i - 1]) * 111320.0 * cos, by = (r.lat[i] - r.lat[i - 1]) * 110540.0;
      double px = (lon - r.lon[i - 1]) * 111320.0 * cos, py = (lat - r.lat[i - 1]) * 110540.0;
      double len2 = bx * bx + by * by;
      double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
      double dx = px - t * bx, dy = py - t * by;
      double off = Math.sqrt(dx * dx + dy * dy);
      if (off < bestOff) {
        bestOff = off;
        bestS = cum[i - 1] + t * Math.sqrt(len2);
        double deg = Math.toDegrees(Math.atan2(bx, by));
        bestBearing = deg < 0 ? deg + 360 : deg;
      }
    }
    return new double[] {bestOff, bestS, bestBearing};
  }

  static double[] cumulative(Road r) {
    double[] cum = new double[r.points()];
    for (int i = 1; i < r.points(); i++) cum[i] = cum[i - 1] + Geo.dist(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i]);
    return cum;
  }

  static int typeOf(Camera c) {
    switch (c.kind) {
      case Camera.SECTION_START: case Camera.SECTION_END: return TYPE_SECTION;
      case Camera.RED_LIGHT: return TYPE_LIGHT;
      case Camera.RAIL_LIGHT: return TYPE_RAIL;
      default: return TYPE_CAMERA;
    }
  }

  static String key(Camera c) { return c.id + "/" + c.kind + "/" + c.pair + "/" + c.dir; }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
