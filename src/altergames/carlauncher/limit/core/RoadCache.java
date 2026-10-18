package altergames.carlauncher.limit.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trwała pamięć ostatnio pobranych dróg: gdy nie ma internetu, limity biorą się stąd.
 * Odporna na uszkodzenia: zapis atomowy (plik tymczasowy i zmiana nazwy), uszkodzony plik jest ignorowany i usuwany,
 * rozmiar ograniczony (liczba wpisów i dróg), każdy wpis walidowany przy odczycie.
 */
public final class RoadCache {
  public static final class Entry {
    public final double lat, lon;
    public final int radius;
    public final long savedAtMs;
    public final List<Road> roads;

    Entry(double lat, double lon, int radius, long savedAtMs, List<Road> roads) {
      this.lat = lat; this.lon = lon; this.radius = radius; this.savedAtMs = savedAtMs; this.roads = roads;
    }
  }

  private static final int MAX_FILE = 6_000_000;
  private final File file;
  private final int maxEntries;
  private final int maxWays;
  private final List<Entry> entries = new ArrayList<Entry>();   // od najstarszego do najnowszego
  private boolean dirty;

  public RoadCache(File file, int maxEntries, int maxWays) {
    this.file = file;
    this.maxEntries = Math.max(1, maxEntries);
    this.maxWays = Math.max(10, maxWays);
  }

  public synchronized int size() { return entries.size(); }

  public synchronized boolean isDirty() { return dirty; }

  /** Wczytanie z pliku. Nigdy nie zgłasza wyjątku: każda niezgodność oznacza pustą pamięć. */
  public synchronized void load() {
    entries.clear();
    dirty = false;
    if (file == null || !file.isFile()) return;
    try {
      if (file.length() > MAX_FILE) { file.delete(); return; }
      String text = new String(readAll(file), "UTF-8");
      Map<String, Object> top = Json.obj(Json.parse(text));
      if (top == null || Json.num(top.get("v"), 0) != 1) { file.delete(); return; }
      List<Object> es = Json.arr(top.get("e"));
      if (es == null) { file.delete(); return; }
      for (Object o : es) {
        try {
          Entry e = readEntry(o);
          if (e != null) entries.add(e);
        } catch (RuntimeException ignored) { /* zepsuty wpis pomijamy */ }
      }
      trim();
    } catch (Exception e) {
      entries.clear();
      try { file.delete(); } catch (RuntimeException ignored) { /* nic */ }
    }
  }

  public synchronized void put(double lat, double lon, int radius, List<Road> roads, long wallNowMs) {
    if (roads == null || Double.isNaN(lat) || Double.isNaN(lon)) return;
    entries.add(new Entry(lat, lon, radius, wallNowMs, new ArrayList<Road>(roads)));
    trim();
    dirty = true;
  }

  /** Drogi z wpisów, których okrąg obejmuje punkt (z zapasem 15 m) i które nie są starsze niż maxAgeMs. Najnowsze mają pierwszeństwo. */
  public synchronized List<Road> near(double lat, double lon, long wallNowMs, long maxAgeMs) {
    List<Road> out = new ArrayList<Road>();
    Set<Long> seen = new HashSet<Long>();
    double kLon = Math.cos(Math.toRadians(lat)) * 111320.0;
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry e = entries.get(i);
      long age = Math.max(0, wallNowMs - e.savedAtMs);
      if (age > maxAgeMs) continue;
      double dx = (e.lon - lon) * kLon, dy = (e.lat - lat) * 110540.0;
      if (Math.sqrt(dx * dx + dy * dy) > e.radius - 15) continue;
      for (Road r : e.roads) if (seen.add(r.id)) out.add(r);
    }
    return out;
  }

  /** Zapis atomowy. Zwraca false przy niepowodzeniu (nic się nie psuje, stary plik zostaje). */
  public synchronized boolean save() {
    if (file == null) return false;
    String json;
    try {
      json = Json.write(toJson());
    } catch (RuntimeException e) {
      return false;
    }
    File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
    FileOutputStream out = null;
    try {
      File dir = file.getParentFile();
      if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
      out = new FileOutputStream(tmp);
      out.write(json.getBytes("UTF-8"));
      out.getFD().sync();
      out.close();
      out = null;
      if (!tmp.renameTo(file)) {
        if (file.exists() && !file.delete()) return false;
        if (!tmp.renameTo(file)) return false;
      }
      dirty = false;
      return true;
    } catch (IOException e) {
      return false;
    } finally {
      if (out != null) { try { out.close(); } catch (IOException ignored) { /* nic */ } }
      if (tmp.exists()) { try { tmp.delete(); } catch (RuntimeException ignored) { /* nic */ } }
    }
  }

  private void trim() {
    while (entries.size() > maxEntries) entries.remove(0);
    int ways = 0;
    for (Entry e : entries) ways += e.roads.size();
    while (ways > maxWays && entries.size() > 1) { ways -= entries.get(0).roads.size(); entries.remove(0); }
  }

  /* ---------- serializacja ---------- */

  private Object toJson() {
    Map<String, Object> top = new LinkedHashMap<String, Object>();
    top.put("v", 1.0);
    List<Object> es = new ArrayList<Object>();
    for (Entry e : entries) {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      m.put("la", r6(e.lat)); m.put("lo", r6(e.lon)); m.put("r", (double) e.radius); m.put("t", (double) e.savedAtMs);
      List<Object> ws = new ArrayList<Object>();
      for (Road r : e.roads) ws.add(roadJson(r));
      m.put("w", ws);
      es.add(m);
    }
    top.put("e", es);
    return top;
  }

  private static Object roadJson(Road r) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("i", (double) r.id); m.put("h", r.highway); m.put("o", (double) r.oneway); m.put("l", (double) r.limit);
    m.put("s", (double) r.source.ordinal()); m.put("f", (double) r.limitFwd); m.put("b", (double) r.limitBwd);
    List<Object> g = new ArrayList<Object>(r.points() * 2);
    for (int k = 0; k < r.points(); k++) { g.add(r6(r.lat[k])); g.add(r6(r.lon[k])); }
    m.put("g", g);
    return m;
  }

  private static Entry readEntry(Object o) {
    Map<String, Object> m = Json.obj(o);
    if (m == null) return null;
    double la = Json.num(m.get("la"), Double.NaN), lo = Json.num(m.get("lo"), Double.NaN);
    int r = (int) Json.num(m.get("r"), -1);
    long t = (long) Json.num(m.get("t"), -1);
    if (Double.isNaN(la) || Double.isNaN(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180 || r < 20 || r > 1000 || t < 0) return null;
    List<Object> ws = Json.arr(m.get("w"));
    if (ws == null || ws.size() > OverpassParser.MAX_WAYS) return null;
    List<Road> roads = new ArrayList<Road>();
    for (Object w : ws) {
      try {
        Road road = readRoad(w);
        if (road != null) roads.add(road);
      } catch (RuntimeException ignored) { /* pomijamy */ }
    }
    return new Entry(la, lo, r, t, roads);
  }

  private static Road readRoad(Object o) {
    Map<String, Object> m = Json.obj(o);
    if (m == null) return null;
    List<Object> g = Json.arr(m.get("g"));
    if (g == null || g.size() < 4 || g.size() % 2 != 0 || g.size() > OverpassParser.MAX_POINTS * 2) return null;
    int n = g.size() / 2;
    double[] la = new double[n], lo = new double[n];
    for (int i = 0; i < n; i++) {
      la[i] = Json.num(g.get(2 * i), Double.NaN);
      lo[i] = Json.num(g.get(2 * i + 1), Double.NaN);
      if (Double.isNaN(la[i]) || Double.isNaN(lo[i]) || Math.abs(la[i]) > 90 || Math.abs(lo[i]) > 180) return null;
    }
    int src = (int) Json.num(m.get("s"), 2);
    Road.Source[] all = Road.Source.values();
    if (src < 0 || src >= all.length) return null;
    int ow = (int) Json.num(m.get("o"), 0);
    if (ow < -1 || ow > 1) return null;
    String hw = Json.str(m.get("h"));
    if (!RoadResolver.isDrivable(hw)) return null;
    return new Road((long) Json.num(m.get("i"), -1), hw, ow, validOrUnknown((int) Json.num(m.get("l"), -1)), all[src],
        validOrUnknown((int) Json.num(m.get("f"), -1)), validOrUnknown((int) Json.num(m.get("b"), -1)), la, lo);
  }

  private static int validOrUnknown(int v) { return Maxspeed.validLimit(v) ? v : Maxspeed.UNKNOWN; }

  private static double r6(double v) { return Math.round(v * 1e6) / 1e6; }

  private static byte[] readAll(File f) throws IOException {
    InputStream in = new FileInputStream(f);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), MAX_FILE));
      byte[] buf = new byte[32 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        if (out.size() > MAX_FILE) throw new IOException("za duży");
      }
      return out.toByteArray();
    } finally {
      try { in.close(); } catch (IOException ignored) { /* nic */ }
    }
  }
}
