package altergames.carlauncher.limit.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Fotoradary na żywo z OpenStreetMap (Overpass): bieżące dane o okolicy auta, żeby nowy albo usunięty fotoradar był widoczny od razu,
 * a nie dopiero po następnej dziennej przebudowie paczek. Pobiera okrąg [RADIUS_M] wokół auta w tle (tylko gdy jest sieć), trzyma wynik
 * w pamięci [TTL_MS] i odnawia go, gdy auto zbliża się do krawędzi okręgu. Po błędzie odczekuje coraz dłużej; nigdy nie zgłasza wyjątków.
 * Dane z paczek (offline) zostają podstawą, gdy żywych nie ma: scalaniem zajmuje się [CameraSource].
 */
public final class OnlineCameras {
  /** Pobiera urządzenia kontrolujące ruch w promieniu radiusM metrów (zgłasza FetchException przy każdej porażce). */
  public interface Fetcher {
    List<Camera> fetch(double lat, double lon, int radiusM) throws RoadSource.FetchException;
  }

  /** Wynik pobrania: środek i promień okręgu, urządzenia i czas. */
  public static final class Snapshot {
    public final double lat, lon;
    public final int radiusM;
    public final List<Camera> cameras;
    final long at;
    Snapshot(double lat, double lon, int radiusM, List<Camera> cameras, long at) {
      this.lat = lat; this.lon = lon; this.radiusM = radiusM; this.cameras = cameras; this.at = at;
    }
    /** Czy okrąg o środku (lat, lon) i promieniu r mieści się w pobranym obszarze. */
    public boolean covers(double lat2, double lon2, int r) { return Geo.dist(lat, lon, lat2, lon2) + r <= radiusM; }
  }

  public static final int RADIUS_M = 8000;
  /** Odnawiamy, gdy auto oddaliło się od środka okręgu tak, że do krawędzi zostałoby mniej niż tyle metrów (nie mniej niż promień szukania alertów, [CameraAlerts.SEARCH_M]). */
  public static final int EDGE_M = 5000;
  public static final long TTL_MS = 15L * 60 * 1000, MAX_AGE_MS = 30L * 60 * 1000, MIN_GAP_MS = 20_000, MAX_BACKOFF_MS = 10L * 60 * 1000;

  private final Fetcher fetcher;
  private final LimitEngine.Clock clock;
  private final Executor bg;
  private final TomTomLimits.Gate gate;
  private Snapshot snap;
  private boolean inFlight;
  private long nextAllowedAt;
  private int failures, ok, failed;
  private String lastError = "";

  public OnlineCameras(Fetcher fetcher, LimitEngine.Clock clock, Executor bg, TomTomLimits.Gate gate) {
    this.fetcher = fetcher; this.clock = clock; this.bg = bg; this.gate = gate;
  }

  /** Zleca pobranie w tle, jeśli dla tej pozycji brakuje świeżych danych. Wołać często, jest tanie. */
  public synchronized void ensure(final double lat, final double lon) {
    try {
      long now = clock.mono();
      if (inFlight || now < nextAllowedAt) return;
      Snapshot s = snap;
      boolean fresh = s != null && now - s.at < TTL_MS && Geo.dist(s.lat, s.lon, lat, lon) + EDGE_M <= s.radiusM;
      if (fresh) return;
      if (gate != null && !gate.allowed()) return;
      inFlight = true;
      nextAllowedAt = now + MIN_GAP_MS;
      bg.execute(new Runnable() {
        @Override public void run() {
          List<Camera> got = null;
          RoadSource.FetchException error = null;
          Throwable other = null;
          try {
            got = fetcher.fetch(lat, lon, RADIUS_M);
          } catch (RoadSource.FetchException e) {
            error = e;
          } catch (Throwable t) {
            other = t;
          }
          finish(lat, lon, got, error, other);
        }
      });
    } catch (RuntimeException e) {
      inFlight = false;
      lastError = "ensure: " + e.getClass().getSimpleName();
    }
  }

  private synchronized void finish(double lat, double lon, List<Camera> got, RoadSource.FetchException error, Throwable other) {
    inFlight = false;
    long now = clock.mono();
    if (got != null) {
      snap = new Snapshot(lat, lon, RADIUS_M, Collections.unmodifiableList(new ArrayList<Camera>(got)), now);
      failures = 0;
      ok++;
      return;
    }
    failed++;
    failures++;
    lastError = error != null ? error.getMessage() : String.valueOf(other == null ? "?" : other.getClass().getSimpleName());
    long backoff = Math.min(MAX_BACKOFF_MS, 30_000L << Math.min(failures - 1, 5));
    if (error != null && error.rateLimited) backoff = Math.max(backoff, 5L * 60 * 1000);
    nextAllowedAt = now + backoff;
  }

  /** Ostatnie udane pobranie albo null (brak albo zbyt stare). */
  public synchronized Snapshot snapshot() {
    Snapshot s = snap;
    return s != null && clock.mono() - s.at < MAX_AGE_MS ? s : null;
  }

  public synchronized String diagnostics() {
    return "radary online: ok=" + ok + " błędy=" + failed + " ostatni błąd=" + lastError;
  }

  /* ===================== Overpass ===================== */

  /** Zapytanie Overpass QL o fotoradary i relacje enforcement (razem z punktami, które są ich członkami). */
  public static String buildQuery(double lat, double lon, int radiusM) {
    return String.format(Locale.US,
        "[out:json][timeout:12];(node[\"highway\"=\"speed_camera\"](around:%d,%.6f,%.6f);"
            + "relation[\"type\"=\"enforcement\"][\"enforcement\"~\"^(maxspeed|average_speed|traffic_signals)$\"](around:%d,%.6f,%.6f);)->.a;"
            + ".a out body;node(r.a);out skel;", radiusM, lat, lon, radiusM, lat, lon);
  }

  /** Odpowiedź Overpass (JSON) na urządzenia. Zgłasza FetchException przy złym formacie. */
  public static List<Camera> parse(String json) throws RoadSource.FetchException {
    Object root;
    try {
      root = Json.parse(json);
    } catch (RuntimeException e) {
      throw new RoadSource.FetchException("zła odpowiedź Overpass");
    }
    Map<String, Object> m = Json.obj(root);
    List<Object> els = m == null ? null : Json.arr(m.get("elements"));
    if (els == null) throw new RoadSource.FetchException("zła odpowiedź Overpass");
    final Map<Long, double[]> pos = new HashMap<Long, double[]>();
    List<OsmCameras.TaggedNode> nodes = new ArrayList<OsmCameras.TaggedNode>();
    List<OsmCameras.Rel> rels = new ArrayList<OsmCameras.Rel>();
    for (Object o : els) {
      Map<String, Object> e = Json.obj(o);
      if (e == null) continue;
      Object type = e.get("type");
      long id = (long) Json.num(e.get("id"), -1);
      if (id < 0) continue;
      if ("node".equals(type)) {
        double la = Json.num(e.get("lat"), Double.NaN), lo = Json.num(e.get("lon"), Double.NaN);
        if (Double.isNaN(la) || Double.isNaN(lo)) continue;
        pos.put(id, new double[] {la, lo});
        Map<String, String> tags = stringMap(e.get("tags"));
        if (tags != null && "speed_camera".equals(tags.get("highway"))) nodes.add(new OsmCameras.TaggedNode(id, la, lo, tags));
      } else if ("relation".equals(type)) {
        Map<String, String> tags = stringMap(e.get("tags"));
        List<Object> mem = Json.arr(e.get("members"));
        if (tags == null || mem == null || !"enforcement".equals(tags.get("type"))) continue;
        long[] ids = new long[mem.size()];
        int[] types = new int[mem.size()];
        String[] roles = new String[mem.size()];
        for (int i = 0; i < mem.size(); i++) {
          Map<String, Object> mm = Json.obj(mem.get(i));
          ids[i] = mm == null ? 0 : (long) Json.num(mm.get("ref"), 0);
          Object t = mm == null ? null : mm.get("type");
          types[i] = "node".equals(t) ? OsmCameras.MEMBER_NODE : "way".equals(t) ? OsmCameras.MEMBER_WAY : OsmCameras.MEMBER_RELATION;
          Object ro = mm == null ? null : mm.get("role");
          roles[i] = ro instanceof String ? (String) ro : "";
        }
        rels.add(new OsmCameras.Rel(id, tags, ids, types, roles));
      }
    }
    List<OsmCameras.Cand> cands = OsmCameras.fromOsm(nodes, rels, new OsmCameras.NodePos() {
      @Override public double[] pos(long id) { return pos.get(id); }
    });
    List<Camera> out = new ArrayList<Camera>(cands.size());
    for (OsmCameras.Cand c : cands) {
      // kierunek jazdy z relacji (od "from" do "to"); bez relacji zostaje "oba kierunki"
      out.add(Double.isNaN(c.hint) ? c.cam : c.cam.withDir((int) Math.round(c.hint) % 360));
    }
    return out;
  }

  private static Map<String, String> stringMap(Object o) {
    Map<String, Object> m = Json.obj(o);
    if (m == null) return null;
    Map<String, String> out = new HashMap<String, String>();
    for (Map.Entry<String, Object> e : m.entrySet()) if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
    return out;
  }

  /** Klient Overpass dla fotoradarów (te same serwery i zasady co [OverpassClient]). */
  public static final class OverpassFetcher implements Fetcher {
    private static final int MAX_BODY = 2_000_000;
    private final String[] endpoints;
    private final int connectMs, readMs;
    private final String userAgent;
    private int next;

    public OverpassFetcher(String[] endpoints, int connectMs, int readMs, String userAgent) {
      this.endpoints = endpoints == null || endpoints.length == 0 ? OverpassClient.DEFAULT_ENDPOINTS : endpoints.clone();
      this.connectMs = connectMs; this.readMs = readMs; this.userAgent = userAgent;
    }

    public static OverpassFetcher createDefault() { return new OverpassFetcher(null, 5000, 14000, "InwokacjaLauncher-LimitWidget/1.0 (osobisty)"); }

    @Override public List<Camera> fetch(double lat, double lon, int radiusM) throws RoadSource.FetchException {
      if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new RoadSource.FetchException("zła pozycja");
      String q = buildQuery(lat, lon, radiusM);
      RoadSource.FetchException last = null;
      for (int a = 0; a < Math.min(2, endpoints.length); a++) {
        String ep;
        synchronized (this) { ep = endpoints[(next + a) % endpoints.length]; }
        try {
          List<Camera> r = once(ep, q);
          synchronized (this) { next = (next + a) % endpoints.length; }
          return r;
        } catch (RoadSource.FetchException e) {
          last = e;
          if (e.rateLimited) break;
        }
      }
      synchronized (this) { next = (next + 1) % endpoints.length; }
      throw last != null ? last : new RoadSource.FetchException("brak serwera");
    }

    private List<Camera> once(String endpoint, String query) throws RoadSource.FetchException {
      HttpURLConnection c = null;
      try {
        c = (HttpURLConnection) new URL(endpoint + "?data=" + URLEncoder.encode(query, "UTF-8")).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code == 429) throw new RoadSource.FetchException("HTTP 429", true, null);
        if (code != 200) throw new RoadSource.FetchException("HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
          byte[] buf = new byte[16 * 1024];
          int n, total = 0;
          while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY) throw new RoadSource.FetchException("odpowiedź za duża");
            out.write(buf, 0, n);
          }
        } finally {
          try { in.close(); } catch (IOException ignored) { /* nic */ }
        }
        return parse(new String(out.toByteArray(), "UTF-8"));
      } catch (RoadSource.FetchException e) {
        throw e;
      } catch (IOException e) {
        throw new RoadSource.FetchException("sieć: " + e.getClass().getSimpleName(), false, e);
      } catch (RuntimeException e) {
        throw new RoadSource.FetchException("błąd: " + e.getClass().getSimpleName(), false, e);
      } finally {
        if (c != null) { try { c.disconnect(); } catch (RuntimeException ignored) { /* nic */ } }
      }
    }
  }
}
