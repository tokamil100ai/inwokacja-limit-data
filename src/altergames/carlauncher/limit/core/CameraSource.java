package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Źródło urządzeń dla ostrzegania: dane z paczek (offline: OSM z dnia budowy plus wykaz ITD) scalone z żywymi danymi z OSM, gdy
 * te są dostępne dla okolicy. Żywe mają pierwszeństwo: nowy fotoradar z OSM jest dodany, a fotoradar, którego z OSM usunięto (i który
 * nie jest w wykazie ITD), znika. Urządzenia znane tylko z ITD zostają zawsze, bo Overpass ich nie zna. Gdy żywych danych nie ma
 * (brak sieci, błąd, zbyt stare), zostają same paczki.
 */
public final class CameraSource implements CameraAlerts.Source {
  static final double MATCH_M = 45, MATCH_SECTION_M = 90;

  private final CameraAlerts.Source offline;
  private final OnlineCameras online;
  private final Map<Camera, Boolean> live = Collections.synchronizedMap(new IdentityHashMap<Camera, Boolean>());

  /** online może być null (same dane offline). */
  public CameraSource(CameraAlerts.Source offline, OnlineCameras online) { this.offline = offline; this.online = online; }

  /** Czy urządzenie z ostatniego wyniku potwierdzono danymi na żywo. */
  public boolean isLive(Camera c) { return live.containsKey(c); }

  @Override public List<Camera> near(double lat, double lon, int radiusM) {
    List<Camera> off = offline.near(lat, lon, radiusM);
    if (online == null) return off;
    online.ensure(lat, lon);
    OnlineCameras.Snapshot sn = online.snapshot();
    live.clear();
    if (sn == null || !sn.covers(lat, lon, radiusM)) return off;
    return merge(off, sn);
  }

  /** Scalenie: patrz opis klasy. */
  List<Camera> merge(List<Camera> off, OnlineCameras.Snapshot sn) {
    List<Camera> out = new ArrayList<Camera>();
    boolean[] used = new boolean[sn.cameras.size()];
    for (Camera c : off) {
      int j = match(c, sn.cameras, used);
      if (j >= 0) {
        used[j] = true;
        Camera o = sn.cameras.get(j);
        Camera m = new Camera(c.id, c.kind, o.lat, o.lon, c.hasDirection() ? c.dir : o.dir, o.limit > 0 ? o.limit : c.limit,
            c.sectionLen > 0 ? c.sectionLen : o.sectionLen, c.sources | Camera.SRC_OSM, c.pair != 0 ? c.pair : o.pair);
        out.add(m);
        live.put(m, Boolean.TRUE);
      } else if ((c.sources & Camera.SRC_OSM) != 0 && (c.sources & Camera.SRC_ITD) == 0
          && Geo.dist(sn.lat, sn.lon, c.lat, c.lon) <= sn.radiusM - 100) {
        // znany z OSM w dniu budowy, ale na żywo go nie ma (usunięty z mapy) i nie jest w wykazie ITD: pomijamy
      } else {
        out.add(c);
      }
    }
    for (int i = 0; i < used.length; i++) {
      if (used[i]) continue;
      Camera o = sn.cameras.get(i);
      if (Geo.dist(sn.lat, sn.lon, o.lat, o.lon) > sn.radiusM) continue;
      out.add(o);
      live.put(o, Boolean.TRUE);
    }
    return out;
  }

  private static int match(Camera c, List<Camera> online, boolean[] used) {
    boolean section = c.kind == Camera.SECTION_START || c.kind == Camera.SECTION_END;
    int best = -1;
    double bd = section ? MATCH_SECTION_M : MATCH_M;
    for (int i = 0; i < online.size(); i++) {
      if (used[i]) continue;
      Camera o = online.get(i);
      if (!sameFamily(c, o)) continue;
      double d = Geo.dist(c.lat, c.lon, o.lat, o.lon);
      if (d <= bd) { bd = d; best = i; }
    }
    return best;
  }

  private static boolean sameFamily(Camera a, Camera b) {
    if (a.kind == b.kind) return true;
    boolean la = a.kind == Camera.RED_LIGHT || a.kind == Camera.RAIL_LIGHT, lb = b.kind == Camera.RED_LIGHT || b.kind == Camera.RAIL_LIGHT;
    return la && lb;
  }
}
