package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.Camera;
import altergames.carlauncher.limit.core.Geo;
import altergames.carlauncher.limit.core.Maxspeed;
import altergames.carlauncher.limit.core.OsmCameras;
import altergames.carlauncher.limit.core.Road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Budowa listy radarów z dwóch źródeł i mądre ich łączenie:
 * <ul>
 * <li>OpenStreetMap: punkty highway=speed_camera oraz relacje enforcement (maxspeed, traffic_signals, average_speed);
 *     relacje dają kierunek jazdy (od "from" do "to"), limit i początek/koniec odcinkowego pomiaru,</li>
 * <li>wykaz ITD (patrz [ItdCsv]): urzędowa lista urządzeń, ale bez limitów i bez kierunków punktowych radarów.</li>
 * </ul>
 * Urządzenie widoczne w obu źródłach zostaje jednym wpisem z oboma znacznikami źródła (pozycja i limit z OSM, długość odcinka z ITD).
 * Kierunek jazdy ustalamy tylko z pewnych danych: z relacji, z odcinka ITD albo z drogi jednokierunkowej. Sam tag "direction"
 * w OSM mówi, w którą stronę patrzy kamera, a nie którym pasem jedzie kontrolowany ruch, więc go nie używamy (brak kierunku =
 * ostrzegamy w obu, co jest mniejszym złem niż pominięcie radaru).
 */
public final class CameraBuilder {
  static final double ITD_POINT_M = 50, ITD_SECTION_M = 120, SNAP_M = 30, DUP_M = 10;

  private CameraBuilder() { }

  // ---------------------------------------------------------------- scalanie

  /** Dołącza urządzenia z wykazu ITD do listy z OSM: dopasowane tylko dostają znacznik ITD, reszta jest dopisywana. */
  static List<OsmCameras.Cand> merge(List<OsmCameras.Cand> osm, List<Camera> itd) {
    List<OsmCameras.Cand> out = new ArrayList<OsmCameras.Cand>(osm);
    Map<Long, OsmCameras.Cand[]> osmSec = sections(osm);
    Map<Long, Camera[]> itdSec = new HashMap<Long, Camera[]>();
    for (Camera c : itd) {
      if (c.kind == Camera.SECTION_START || c.kind == Camera.SECTION_END) {
        Camera[] s = itdSec.get(c.pair);
        if (s == null) { s = new Camera[2]; itdSec.put(c.pair, s); }
        s[c.kind == Camera.SECTION_START ? 0 : 1] = c;
      }
    }
    Set<Long> usedOsmSec = new HashSet<Long>();
    // sortowanie po kluczu dla powtarzalności wyniku
    List<Long> keys = new ArrayList<Long>(itdSec.keySet());
    Collections.sort(keys);
    for (long key : keys) {
      Camera[] s = itdSec.get(key);
      if (s[0] == null || s[1] == null) continue;
      long bestKey = 0;
      double best = Double.MAX_VALUE;
      for (Map.Entry<Long, OsmCameras.Cand[]> e : osmSec.entrySet()) {
        OsmCameras.Cand[] o = e.getValue();
        if (o[0] == null || o[1] == null) continue;
        double d1 = Geo.dist(s[0].lat, s[0].lon, o[0].cam.lat, o[0].cam.lon), d2 = Geo.dist(s[1].lat, s[1].lon, o[1].cam.lat, o[1].cam.lon);
        if (d1 <= ITD_SECTION_M && d2 <= ITD_SECTION_M && d1 + d2 < best) { best = d1 + d2; bestKey = e.getKey(); }
      }
      if (best < Double.MAX_VALUE) {
        OsmCameras.Cand[] o = osmSec.get(bestKey);
        usedOsmSec.add(bestKey);
        int len = s[0].sectionLen > 0 ? s[0].sectionLen : o[0].cam.sectionLen;
        o[0].cam = new Camera(o[0].cam.id, o[0].cam.kind, o[0].cam.lat, o[0].cam.lon, o[0].cam.dir, o[0].cam.limit, len,
            o[0].cam.sources | Camera.SRC_ITD, o[0].cam.pair);
        o[1].cam = o[1].cam.withSources(o[1].cam.sources | Camera.SRC_ITD);
      } else {
        double h = Geo.bearing(new double[] {s[0].lat, s[0].lon}, new double[] {s[1].lat, s[1].lon});
        out.add(new OsmCameras.Cand(s[0], h));
        out.add(new OsmCameras.Cand(s[1], h));
      }
    }
    for (Camera c : itd) {
      if (c.kind == Camera.SECTION_START || c.kind == Camera.SECTION_END) continue;
      OsmCameras.Cand best = null;
      double bd = ITD_POINT_M;
      for (OsmCameras.Cand o : osm) {
        if (o.cam.kind == Camera.SECTION_START || o.cam.kind == Camera.SECTION_END || family(o.cam.kind) != family(c.kind)) continue;
        double d = Geo.dist(c.lat, c.lon, o.cam.lat, o.cam.lon);
        if (d <= bd) { bd = d; best = o; }
      }
      if (best != null) {
        best.cam = best.cam.withSources(best.cam.sources | Camera.SRC_ITD);
        if (c.kind == Camera.RAIL_LIGHT && best.cam.kind == Camera.RED_LIGHT) {
          best.cam = new Camera(best.cam.id, Camera.RAIL_LIGHT, best.cam.lat, best.cam.lon, best.cam.dir, best.cam.limit, 0, best.cam.sources, 0);
        }
      } else {
        out.add(new OsmCameras.Cand(c, Double.NaN));
      }
    }
    return out;
  }

  private static Map<Long, OsmCameras.Cand[]> sections(List<OsmCameras.Cand> l) {
    Map<Long, OsmCameras.Cand[]> m = new HashMap<Long, OsmCameras.Cand[]>();
    for (OsmCameras.Cand c : l) {
      if (c.cam.kind != Camera.SECTION_START && c.cam.kind != Camera.SECTION_END) continue;
      OsmCameras.Cand[] s = m.get(c.cam.pair);
      if (s == null) { s = new OsmCameras.Cand[2]; m.put(c.cam.pair, s); }
      s[c.cam.kind == Camera.SECTION_START ? 0 : 1] = c;
    }
    return m;
  }

  private static int family(int kind) { return kind == Camera.SPEED ? 0 : 1; }

  /** Usuwa powtórzenia: ten sam rodzaj w odległości do 10 m, o ile kierunki się nie wykluczają. */
  static List<OsmCameras.Cand> dedupe(List<OsmCameras.Cand> in) {
    List<OsmCameras.Cand> sorted = new ArrayList<OsmCameras.Cand>(in);
    Collections.sort(sorted, new Comparator<OsmCameras.Cand>() {
      public int compare(OsmCameras.Cand a, OsmCameras.Cand b) {
        int c = Integer.compare(Integer.bitCount(b.cam.sources), Integer.bitCount(a.cam.sources));   // lepiej udokumentowane najpierw
        if (c != 0) return c;
        return Long.compare(a.cam.id, b.cam.id);
      }
    });
    List<OsmCameras.Cand> kept = new ArrayList<OsmCameras.Cand>();
    for (OsmCameras.Cand c : sorted) {
      boolean dup = false;
      boolean sec = c.cam.kind == Camera.SECTION_START || c.cam.kind == Camera.SECTION_END;
      if (!sec) {
        for (OsmCameras.Cand k : kept) {
          if (k.cam.kind != c.cam.kind) continue;
          if (Geo.dist(c.cam.lat, c.cam.lon, k.cam.lat, k.cam.lon) > DUP_M) continue;
          if (!Double.isNaN(c.hint) && !Double.isNaN(k.hint) && Geo.angDiff(c.hint, k.hint) > 90) continue;   // przeciwne kierunki: dwa urządzenia
          dup = true;
          k.cam = k.cam.withSources(k.cam.sources | c.cam.sources);
          break;
        }
      }
      if (!dup) kept.add(c);
    }
    return kept;
  }

  // ---------------------------------------------------------------- kierunek z najbliższej drogi

  /** Ustala kierunek jazdy każdego urządzenia (patrz opis klasy) i zwraca gotową listę, posortowaną dla powtarzalności. */
  static List<Camera> orient(List<OsmCameras.Cand> cands, List<Road> roads) {
    // siatka gruba (0,01 stopnia): tylko komórki z urządzeniami, droga trafia do komórek, które zahacza jej odcinek (z zapasem 40 m)
    Set<Long> wanted = new HashSet<Long>();
    for (OsmCameras.Cand c : cands) wanted.add(cell(c.cam.lat, c.cam.lon));
    Map<Long, List<Road>> grid = new HashMap<Long, List<Road>>();
    for (Road r : roads) {
      Set<Long> mine = new HashSet<Long>();
      for (int i = 1; i < r.points(); i++) {
        double la0 = Math.min(r.lat[i - 1], r.lat[i]) - 0.0004, la1 = Math.max(r.lat[i - 1], r.lat[i]) + 0.0004;
        double lo0 = Math.min(r.lon[i - 1], r.lon[i]) - 0.0007, lo1 = Math.max(r.lon[i - 1], r.lon[i]) + 0.0007;
        int y0 = (int) Math.floor(la0 * 100), y1 = (int) Math.floor(la1 * 100), x0 = (int) Math.floor(lo0 * 100), x1 = (int) Math.floor(lo1 * 100);
        if ((long) (y1 - y0 + 1) * (x1 - x0 + 1) > 2500) continue;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
          long k = ((long) y << 32) ^ (x & 0xFFFFFFFFL);
          if (wanted.contains(k)) mine.add(k);
        }
      }
      for (long k : mine) {
        List<Road> l = grid.get(k);
        if (l == null) { l = new ArrayList<Road>(); grid.put(k, l); }
        l.add(r);
      }
    }
    List<Camera> out = new ArrayList<Camera>(cands.size());
    for (OsmCameras.Cand c : cands) {
      Camera cam = c.cam;
      Road best = null;
      int bestSeg = -1;
      double bestD = SNAP_M + 1;
      List<Road> near = grid.get(cell(cam.lat, cam.lon));
      if (near != null) {
        for (Road r : near) {
          double penalty = "service".equals(r.highway) || "living_street".equals(r.highway) ? 6 : 0;
          for (int i = 1; i < r.points(); i++) {
            double d = Geo.segDist(cam.lat, cam.lon, new double[] {r.lat[i - 1], r.lon[i - 1]}, new double[] {r.lat[i], r.lon[i]}) + penalty;
            if (d < bestD) { bestD = d; best = r; bestSeg = i; }
          }
        }
      }
      double dir = Double.NaN;
      if (best != null) {
        double b = Geo.bearing(new double[] {best.lat[bestSeg - 1], best.lon[bestSeg - 1]}, new double[] {best.lat[bestSeg], best.lon[bestSeg]});
        if (best.oneway == 1) dir = b;
        else if (best.oneway == -1) dir = b + 180;
        else if (!Double.isNaN(c.hint)) dir = Geo.angDiff(b, c.hint) <= 90 ? b : b + 180;
      } else if (!Double.isNaN(c.hint)) {
        dir = c.hint;
      }
      int d = Double.isNaN(dir) ? Camera.DIR_ANY : (int) Math.round(((dir % 360) + 360) % 360) % 360;
      out.add(new Camera(cam.id, cam.kind, cam.lat, cam.lon, d, cam.limit, cam.sectionLen, cam.sources, cam.pair));
    }
    // po ustaleniu kierunku różne wpisy mogą się okazać tym samym (np. dwie relacje z przeciwnymi wskazówkami na drodze jednokierunkowej)
    Set<String> seenKeys = new HashSet<String>();
    List<Camera> unique = new ArrayList<Camera>(out.size());
    for (Camera c : out) if (seenKeys.add(c.id + "|" + c.kind + "|" + c.pair + "|" + c.dir + "|" + c.lat + "|" + c.lon)) unique.add(c);
    out = unique;
    Collections.sort(out, new Comparator<Camera>() {
      public int compare(Camera a, Camera b) {
        int c = Double.compare(a.lat, b.lat);
        if (c != 0) return c;
        c = Double.compare(a.lon, b.lon);
        if (c != 0) return c;
        c = Integer.compare(a.kind, b.kind);
        return c != 0 ? c : Long.compare(a.id, b.id);
      }
    });
    return out;
  }

  private static long cell(double lat, double lon) {
    return ((long) Math.floor(lat * 100) << 32) ^ ((int) Math.floor(lon * 100) & 0xFFFFFFFFL);
  }

  /** Całość: OSM + ITD (może być null) + drogi -> gotowe urządzenia. */
  public static List<Camera> build(List<OsmCameras.TaggedNode> nodes, List<OsmCameras.Rel> rels, OsmCameras.NodePos pos, List<Camera> itd, List<Road> roads) {
    List<OsmCameras.Cand> osm = OsmCameras.fromOsm(nodes, rels, pos);
    List<OsmCameras.Cand> merged = merge(osm, itd == null ? Collections.<Camera>emptyList() : itd);
    return orient(dedupe(merged), roads);
  }

}
