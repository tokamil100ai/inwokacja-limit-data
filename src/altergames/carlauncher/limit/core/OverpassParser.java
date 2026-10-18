package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Odpowiedź Overpass API (out tags geom) na listę dróg. Błędne elementy są pomijane, a nie przerywają całości. */
public final class OverpassParser {
  private OverpassParser() {}

  public static final int MAX_WAYS = 500;
  public static final int MAX_POINTS = 3000;

  /** Zgłasza Json.JsonException tylko przy całkowicie nieprawidłowej odpowiedzi lub błędzie zgłoszonym przez serwer. */
  public static List<Road> parse(String json) {
    Object root = Json.parse(json);
    Map<String, Object> top = Json.obj(root);
    if (top == null) throw new Json.JsonException("odpowiedź nie jest obiektem");
    String remark = Json.str(top.get("remark"));
    if (remark != null && remark.toLowerCase(java.util.Locale.ROOT).contains("error")) {
      throw new Json.JsonException("serwer zgłosił błąd: " + remark);   // np. przekroczony czas zapytania, dane niepełne
    }
    List<Object> els = Json.arr(top.get("elements"));
    if (els == null) throw new Json.JsonException("brak pola elements");
    List<Road> out = new ArrayList<Road>();
    for (Object e : els) {
      if (out.size() >= MAX_WAYS) break;
      try {
        Road r = one(e);
        if (r != null) out.add(r);
      } catch (RuntimeException ex) {
        // pojedynczy zepsuty element nie psuje reszty
      }
    }
    return out;
  }

  private static Road one(Object e) {
    Map<String, Object> m = Json.obj(e);
    if (m == null || !"way".equals(Json.str(m.get("type")))) return null;
    double idd = Json.num(m.get("id"), Double.NaN);
    if (Double.isNaN(idd)) return null;
    Map<String, Object> tagsRaw = Json.obj(m.get("tags"));
    if (tagsRaw == null) return null;
    Map<String, String> tags = new HashMap<String, String>();
    for (Map.Entry<String, Object> t : tagsRaw.entrySet()) {
      String v = Json.str(t.getValue());
      if (v != null && t.getKey() != null && t.getKey().length() <= 64 && v.length() <= 256) tags.put(t.getKey(), v);
    }
    List<Object> g = Json.arr(m.get("geometry"));
    if (g == null || g.size() < 2 || g.size() > MAX_POINTS) return null;
    double[] la = new double[g.size()], lo = new double[g.size()];
    int n = 0;
    for (Object p : g) {
      Map<String, Object> pm = Json.obj(p);
      if (pm == null) continue;   // Overpass daje null dla punktów poza obszarem, pomijamy
      double a = Json.num(pm.get("lat"), Double.NaN), b = Json.num(pm.get("lon"), Double.NaN);
      if (Double.isNaN(a) || Double.isNaN(b) || Math.abs(a) > 90 || Math.abs(b) > 180) continue;
      la[n] = a; lo[n] = b; n++;
    }
    if (n < 2) return null;
    if (n != la.length) { la = java.util.Arrays.copyOf(la, n); lo = java.util.Arrays.copyOf(lo, n); }
    return RoadResolver.fromTags((long) idd, tags, la, lo);
  }
}
