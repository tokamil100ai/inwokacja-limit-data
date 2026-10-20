package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zamiana danych OpenStreetMap o urządzeniach kontrolujących ruch na [Camera]: punkty highway=speed_camera oraz relacje enforcement
 * (maxspeed, traffic_signals, average_speed). Relacje dają kierunek jazdy (od "from" do "to"), limit i początek/koniec odcinkowego
 * pomiaru. Używane zarówno przez program budujący paczki (z pliku PBF), jak i przez aplikację (z odpowiedzi Overpass na żywo), więc
 * oba źródła dają te same urządzenia. Sam tag "direction" w OSM mówi, w którą stronę patrzy kamera, a nie którym pasem jedzie
 * kontrolowany ruch, więc go nie używamy. Czysta Java.
 */
public final class OsmCameras {
  public static final int MEMBER_NODE = 0, MEMBER_WAY = 1, MEMBER_RELATION = 2;
  static final double DEVICE_NEAR_M = 150;

  private OsmCameras() { }

  /** Punkt OSM z tagami (filtr: highway=speed_camera). */
  public static final class TaggedNode {
    public final long id; public final double lat, lon; public final Map<String, String> tags;
    public TaggedNode(long id, double lat, double lon, Map<String, String> tags) { this.id = id; this.lat = lat; this.lon = lon; this.tags = tags; }
  }

  /** Relacja OSM typu enforcement. */
  public static final class Rel {
    public final long id; public final Map<String, String> tags; public final long[] mem; public final int[] types; public final String[] roles;
    public Rel(long id, Map<String, String> tags, long[] mem, int[] types, String[] roles) {
      this.id = id; this.tags = tags; this.mem = mem; this.types = types; this.roles = roles;
    }
  }

  /** Położenie punktu OSM: {szerokość, długość} albo null, gdy nie znamy. */
  public interface NodePos { double[] pos(long id); }

  /** Urządzenie z ewentualną wskazówką kierunku jazdy (azymut w stopniach 0..360, NaN gdy brak). */
  public static final class Cand {
    public Camera cam;
    public double hint = Double.NaN;
    public Cand(Camera cam, double hint) { this.cam = cam; this.hint = hint; }
  }


  public static List<Cand> fromOsm(List<TaggedNode> nodes, List<Rel> rels, NodePos pos) {
    List<Cand> out = new ArrayList<Cand>();
    Map<Long, TaggedNode> byId = new HashMap<Long, TaggedNode>();
    for (TaggedNode n : nodes) byId.put(n.id, n);
    Set<Long> consumed = new HashSet<Long>();

    for (Rel r : rels) {
      String kind = r.tags.get("enforcement");
      if (kind == null) continue;
      double[] from = memberPos(r, "from", pos), to = memberPos(r, "to", pos);
      double hint = from != null && to != null && Geo.dist(from, to) > 10 ? Geo.bearing(from, to) : Double.NaN;
      List<long[]> devIds = new ArrayList<long[]>();           // {id}
      List<double[]> devPos = new ArrayList<double[]>();
      for (int i = 0; i < r.mem.length; i++) {
        if (r.types[i] != MEMBER_NODE || !"device".equals(r.roles[i])) continue;
        double[] p = pos.pos(r.mem[i]);
        if (p != null) { devIds.add(new long[] {r.mem[i]}); devPos.add(p); }
      }
      int relLimit = Maxspeed.parse(r.tags.get("maxspeed"));

      if ("maxspeed".equals(kind) || "traffic_signals".equals(kind)) {
        boolean red = "traffic_signals".equals(kind);
        if (devIds.isEmpty() && !red && from != null && to != null) {
          // relacja bez urządzenia: bierzemy radar zmapowany jako punkt, który leży przy odcinku od "from" do "to"
          for (TaggedNode n : nodes) {
            if (consumed.contains(n.id)) continue;
            if (Geo.segDist(n.lat, n.lon, from, to) <= 25) { devIds.add(new long[] {n.id}); devPos.add(new double[] {n.lat, n.lon}); }
          }
        }
        for (int i = 0; i < devIds.size(); i++) {
          long id = devIds.get(i)[0];
          consumed.add(id);
          TaggedNode tn = byId.get(id);
          int lim = relLimit != Maxspeed.UNKNOWN ? relLimit : tn != null ? Maxspeed.parse(tn.tags.get("maxspeed")) : Maxspeed.UNKNOWN;
          double[] p = devPos.get(i);
          out.add(new Cand(new Camera(id, red ? Camera.RED_LIGHT : Camera.SPEED, p[0], p[1], Camera.DIR_ANY,
              red || lim == Maxspeed.UNKNOWN ? 0 : lim, 0, Camera.SRC_OSM, 0), hint));
        }
      } else if ("average_speed".equals(kind)) {
        if (from == null || to == null) continue;
        double[] sp = nearest(devPos, from, DEVICE_NEAR_M), ep = nearest(devPos, to, DEVICE_NEAR_M);
        long sid = sp == null ? firstMember(r, "from") : idAt(devIds, devPos, sp), eid = ep == null ? firstMember(r, "to") : idAt(devIds, devPos, ep);
        if (sp == null) sp = from;
        if (ep == null) ep = to;
        if (Geo.dist(sp, ep) < 200) continue;                                          // nie odcinek
        consumed.add(sid); consumed.add(eid);
        int len = parseLen(r.tags.get("length"));
        if (len <= 0) len = (int) Math.round(Geo.dist(from, to));
        int lim = relLimit == Maxspeed.UNKNOWN ? 0 : relLimit;
        out.add(new Cand(new Camera(sid, Camera.SECTION_START, sp[0], sp[1], Camera.DIR_ANY, lim, len, Camera.SRC_OSM, r.id), hint));
        out.add(new Cand(new Camera(eid, Camera.SECTION_END, ep[0], ep[1], Camera.DIR_ANY, lim, 0, Camera.SRC_OSM, r.id), hint));
      }
    }
    for (TaggedNode n : nodes) {
      if (consumed.contains(n.id)) continue;
      int lim = Maxspeed.parse(n.tags.get("maxspeed"));
      out.add(new Cand(new Camera(n.id, Camera.SPEED, n.lat, n.lon, Camera.DIR_ANY, lim == Maxspeed.UNKNOWN ? 0 : lim, 0, Camera.SRC_OSM, 0), Double.NaN));
    }
    return out;
  }

  private static double[] memberPos(Rel r, String role, NodePos pos) {
    for (int i = 0; i < r.mem.length; i++) {
      if (r.types[i] == MEMBER_NODE && role.equals(r.roles[i])) {
        double[] p = pos.pos(r.mem[i]);
        if (p != null) return p;
      }
    }
    return null;
  }

  private static long firstMember(Rel r, String role) {
    for (int i = 0; i < r.mem.length; i++) if (r.types[i] == MEMBER_NODE && role.equals(r.roles[i])) return r.mem[i];
    return 0;
  }

  private static long idAt(List<long[]> ids, List<double[]> poss, double[] p) {
    for (int i = 0; i < poss.size(); i++) if (poss.get(i) == p) return ids.get(i)[0];
    return 0;
  }

  private static double[] nearest(List<double[]> cands, double[] to, double maxM) {
    double[] best = null;
    double bd = maxM;
    for (double[] c : cands) {
      double d = Geo.dist(c, to);
      if (d <= bd) { bd = d; best = c; }
    }
    return best;
  }

  private static int parseLen(String s) {
    if (s == null) return 0;
    try {
      double v = Double.parseDouble(s.trim().replace(',', '.'));
      return v > 0 && v < 100_000 ? (int) Math.round(v) : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
