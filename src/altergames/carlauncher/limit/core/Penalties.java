package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Tabela mandatów i punktów karnych za przekroczenie prędkości. Wbudowana kopia albo plik JSON z sieci. */
public final class Penalties {
  public static final class Band {
    public final int from;
    public final int to;          // Integer.MAX_VALUE dla ostatniego pasma
    public final int fine;
    public final int points;
    public final int repeatFine;  // 0 gdy brak

    Band(int from, int to, int fine, int points, int repeatFine) {
      this.from = from; this.to = to; this.fine = fine; this.points = points; this.repeatFine = repeatFine;
    }
  }

  public final int version;
  public final String updated;
  public final List<Band> bands;

  private Penalties(int version, String updated, List<Band> bands) {
    this.version = version;
    this.updated = updated == null ? "" : updated;
    this.bands = Collections.unmodifiableList(bands);
  }

  /** Kopia awaryjna (Rankomat.pl, tabela z 13.07.2026), identyczna z penalties.json w repozytorium. */
  public static Penalties builtin() {
    List<Band> b = new ArrayList<Band>();
    b.add(new Band(1, 10, 50, 1, 0));
    b.add(new Band(11, 15, 100, 2, 0));
    b.add(new Band(16, 20, 200, 3, 0));
    b.add(new Band(21, 25, 300, 5, 0));
    b.add(new Band(26, 30, 400, 7, 0));
    b.add(new Band(31, 40, 800, 9, 1600));
    b.add(new Band(41, 50, 1000, 11, 2000));
    b.add(new Band(51, 60, 1500, 13, 3000));
    b.add(new Band(61, 70, 2000, 14, 4000));
    b.add(new Band(71, Integer.MAX_VALUE, 2500, 15, 5000));
    return new Penalties(1, "2026-10-18", b);
  }

  /** Pasmo dla przekroczenia (po odjęciu tolerancji) w km/h albo null, gdy to poniżej pierwszego pasma. */
  public Band find(int excessKmh) {
    if (excessKmh < 1) return null;
    for (Band b : bands) if (excessKmh >= b.from && excessKmh <= b.to) return b;
    return null;
  }

  /**
   * Odczyt i walidacja tabeli z JSON. Zgłasza IllegalArgumentException przy każdej niezgodności,
   * żeby uszkodzony lub podmieniony plik nigdy nie zepsuł wyświetlania (wtedy zostaje poprzednia tabela).
   */
  public static Penalties parse(String json) {
    if (json == null || json.length() > 65_536) throw new IllegalArgumentException("zły rozmiar");
    Object root;
    try { root = Json.parse(json); } catch (Json.JsonException e) { throw new IllegalArgumentException("zły JSON: " + e.getMessage()); }
    Map<String, Object> top = Json.obj(root);
    if (top == null) throw new IllegalArgumentException("to nie obiekt");
    List<Object> arr = Json.arr(top.get("bands"));
    if (arr == null || arr.isEmpty() || arr.size() > 40) throw new IllegalArgumentException("zła liczba pasm");
    List<Band> out = new ArrayList<Band>();
    int prevTo = 0;
    for (int i = 0; i < arr.size(); i++) {
      Map<String, Object> m = Json.obj(arr.get(i));
      if (m == null) throw new IllegalArgumentException("pasmo " + i + " nie jest obiektem");
      int from = intIn(m.get("from"), 1, 1000, "from");
      Object toObj = m.get("to");
      int to = toObj == null ? Integer.MAX_VALUE : intIn(toObj, from, 1000, "to");
      int fine = intIn(m.get("fine"), 1, 100_000, "fine");
      int points = intIn(m.get("points"), 0, 30, "points");
      Object rf = m.get("repeatFine");
      int repeat = rf == null ? 0 : intIn(rf, 0, 1_000_000, "repeatFine");
      if (from != prevTo + 1) throw new IllegalArgumentException("pasma nie przylegają przy " + i);
      if (to == Integer.MAX_VALUE && i != arr.size() - 1) throw new IllegalArgumentException("nieskończone pasmo nie na końcu");
      out.add(new Band(from, to, fine, points, repeat));
      prevTo = to;
      if (to == Integer.MAX_VALUE) break;
    }
    if (out.get(0).from != 1) throw new IllegalArgumentException("pierwsze pasmo musi zaczynać się od 1");
    Object ver = top.get("version");
    String upd = Json.str(top.get("updated"));
    return new Penalties(ver instanceof Double ? (int) (double) (Double) ver : 0, upd != null && upd.length() <= 32 ? upd : "", out);
  }

  private static int intIn(Object o, int lo, int hi, String name) {
    if (!(o instanceof Double)) throw new IllegalArgumentException(name + " nie jest liczbą");
    double d = (Double) o;
    if (d != Math.rint(d) || d < lo || d > hi) throw new IllegalArgumentException(name + " poza zakresem");
    return (int) d;
  }

  /** Kwota po polsku: 1500 zł, ale 10 000 zł (spacja niełamliwa), jak w prototypie. */
  public static String formatPln(int n) {
    String s = Integer.toString(Math.max(0, n));
    if (s.length() >= 5) {
      StringBuilder b = new StringBuilder();
      int first = s.length() % 3;
      if (first > 0) b.append(s, 0, first);
      for (int i = first; i < s.length(); i += 3) {
        if (b.length() > 0) b.append(' ');
        b.append(s, i, i + 3);
      }
      s = b.toString();
    }
    return s + " zł";
  }
}
