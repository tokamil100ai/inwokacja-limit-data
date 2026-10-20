package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.Maxspeed;
import altergames.carlauncher.limit.core.Road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rozpoznawanie terenu zabudowanego dla dróg bez limitu, żeby w aplikacji rzadziej widać było "?".
 * UWAGA: w budowie paczek jest WYŁĄCZONE (RdbBuilder --urban je włącza). Pomiar pokazał, że nawet przy ścisłych regułach 12 do 17% dróg
 * z jawnym limitem w przewidzianych obszarach ma limit niższy niż przewidziany (strefy 30, ograniczenia 60 do 80), czyli pokazalibyśmy
 * limit za wysoki, a to groźniejsze niż "?". Szczegóły: docs/HANDOFF-limit-widget.md, sekcja 4.5c.
 * W Polsce na drodze bez znaku obowiązuje 50 km/h w terenie zabudowanym i 90 km/h poza nim, a tagi OSM nie mówią, który to teren.
 * Najlepszym sygnałem okazała się gęstość budynków wokół punktu drogi (zmierzona na drogach z jawnym limitem 50 albo 90,
 * patrz docs/HANDOFF-limit-widget.md, sekcja 4.5c); gęstość samych dróg i znaczniki miejscowości są dużo słabsze.
 *
 * Droga jest próbkowana co 30 m, każda próbka dostaje jedną z trzech etykiet:
 * <ul>
 * <li>zabudowana (50): w oknie 3 x 3 komórek (ok. 330 x 315 m) co najmniej 30 budynków (35 na drodze unclassified),
 *     a w oknie 5 x 5 komórek nie ma żadnej drogi z jawnym limitem 90;</li>
 * <li>niezabudowana (90), tylko secondary i tertiary, nie jednokierunkowe (dwujezdniowa ma inny limit): w oknie 7 x 7 komórek
 *     (ok. 770 m) najwyżej 8 budynków, w oknie 5 x 5 brak drogi z jawnym limitem 50, w okolicy (3 x 3 grubych komórek, ok. 2,6 km)
 *     jest co najmniej 20 budynków (inaczej dane o budynkach są podejrzanie puste, np. wycinek bez zabudowy), nie ma miejscowości
 *     bliżej niż 400 m, osady bliżej niż 200 m ani znaku granicy miejscowości bliżej niż 250 m;</li>
 * <li>nieznana: wszystko inne.</li>
 * </ul>
 * Pojedyncze próbki różniące się od sąsiadów i krótkie odcinki (poniżej 120 m) są odrzucane. Droga jest cięta na odcinki o jednej
 * etykiecie; odcinek zabudowany dostaje 50, niezabudowany 90 (oba jako ESTIMATE, w aplikacji przerywany pierścień), nieznany zostaje bez limitu.
 * Granica odcinków jest celowo ostrożna: okno budynków sięga dalej niż znak końca miejscowości, więc przy wjeździe do wsi jest
 * pas "nieznane" zamiast błędnego limitu. Odcinki z wyboru nie mają limitu, więc rozpoznanie nigdy nie nadpisuje jawnych danych.
 * Pierwszy odcinek drogi zachowuje identyfikator OSM, kolejne dostają id + (k << 40), żeby identyfikatory dróg w bazie były unikalne.
 */
public final class UrbanInference {
  static final int CELL_LAT = 1000, CELL_LON = 1600;          // komórka siatki w mikrostopniach (ok. 111 x 105 m)
  static final int BLOCK = 8;                                   // gruba siatka (kontrola pokrycia budynkami): 8 x 8 komórek
  static final double STEP_M = 30.0;
  static final int PART_SHIFT = 40;
  static final long MAX_CELLS = 150_000_000L;

  static final int KIND_SETTLEMENT = 1, KIND_HAMLET = 2, KIND_SIGN = 4;

  private static final byte NONE = 0, URBAN = 1, RURAL = 2;

  private UrbanInference() { }

  /** Progi rozpoznawania; domyślne wartości wybrano na Pomorskim i sprawdzono na reszcie kraju (sekcja 4.5c dokumentu przekazania). */
  public static final class Params {
    /** Budynków w oknie 3 x 3 komórek, od których próbka jest zabudowana (secondary, tertiary / unclassified). */
    public int urbanMin = 30, urbanMinUnclassified = 35;
    /** Najwyżej tyle budynków w oknie 7 x 7 komórek, żeby próbka była niezabudowana. */
    public int ruralMax = 8;
    /** Najmniej budynków w oknie 3 x 3 grubych komórek (ok. 2,6 km), żeby dane o budynkach uznać za kompletne. */
    public int coverageMin = 20;
    public double settlementGuardM = 400, hamletGuardM = 200, signGuardM = 250;
    /** Krótszych odcinków (poza całą drogą) nie wyodrębniamy. */
    public double minRunM = 120;
    /** Odcinek zabudowany odrzucamy, gdy w oknie 5 x 5 jest droga z jawnym limitem 90; niezabudowany, gdy z jawnym limitem 50. */
    public boolean veto = true;
    /** false wyłącza rozpoznawanie niezabudowane (tylko 50). */
    public boolean rural = true;
  }

  /** Wynik rozpoznawania (do statystyk budowy i do narzędzi diagnostycznych). */
  public static final class Result {
    public int candidates, splitRoads, urbanParts, ruralParts;
    public double candidateKm, urbanKm, ruralKm;
    @Override public String toString() {
      return "teren: zabudowany " + Math.round(urbanKm) + " km (" + urbanParts + " odc.), niezabudowany " + Math.round(ruralKm) + " km ("
          + ruralParts + " odc.), kandydatów " + candidates + " (" + Math.round(candidateKm) + " km), podzielonych dróg " + splitRoads;
    }
  }

  /** Budynki, miejscowości i znaki granic w siatce, wspólne dla wszystkich dróg. */
  public static final class Context {
    final int lat0, lon0, rows, cols, brows, bcols;
    final char[] bld, coarse;
    long buildings;
    private final Points settlements = new Points(), signs = new Points();

    private Context(int lat0, int lon0, int rows, int cols) {
      this.lat0 = lat0; this.lon0 = lon0; this.rows = rows; this.cols = cols;
      this.brows = (rows + BLOCK - 1) / BLOCK; this.bcols = (cols + BLOCK - 1) / BLOCK;
      this.bld = new char[rows * cols];
      this.coarse = new char[brows * bcols];
    }

    /** Siatka obejmująca podany prostokąt (z zapasem), albo null, gdy byłaby nierozsądnie duża. */
    public static Context create(double minLat, double minLon, double maxLat, double maxLon) {
      if (!(minLat <= maxLat) || !(minLon <= maxLon)) return null;
      // zapas 3 grubych komórek wokół dróg: okno kontroli pokrycia budynkami sięga tak daleko także przy brzegu danych
      int pad = 3 * BLOCK;
      int lat0 = (int) (Math.floor(minLat * 1e6 / CELL_LAT) * CELL_LAT) - pad * CELL_LAT;
      int lon0 = (int) (Math.floor(minLon * 1e6 / CELL_LON) * CELL_LON) - pad * CELL_LON;
      long rows = (long) ((maxLat * 1e6 - lat0) / CELL_LAT) + pad + 2, cols = (long) ((maxLon * 1e6 - lon0) / CELL_LON) + pad + 2;
      if (rows <= 0 || cols <= 0 || rows * cols > MAX_CELLS) return null;
      return new Context(lat0, lon0, (int) rows, (int) cols);
    }

    int cellX(double lon) { return (int) Math.floor((lon * 1e6 - lon0) / CELL_LON); }
    int cellY(double lat) { return (int) Math.floor((lat * 1e6 - lat0) / CELL_LAT); }
    boolean inside(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < cols && cy < rows; }

    public void addBuilding(double lat, double lon) {
      int cx = cellX(lon), cy = cellY(lat);
      if (!inside(cx, cy)) return;
      int i = cy * cols + cx;
      if (bld[i] != Character.MAX_VALUE) bld[i]++;
      int j = (cy / BLOCK) * bcols + cx / BLOCK;
      if (coarse[j] != Character.MAX_VALUE) coarse[j]++;
      buildings++;
    }

    /** Miejscowość z tagu place: city, town, village, suburb, neighbourhood, quarter, borough (duże) lub hamlet, isolated_dwelling (osada). */
    public void addPlace(double lat, double lon, String place) {
      if (place == null) return;
      switch (place) {
        case "city": case "town": case "village": case "suburb": case "neighbourhood": case "quarter": case "borough":
          settlements.add(lat, lon, KIND_SETTLEMENT); break;
        case "hamlet": case "isolated_dwelling":
          settlements.add(lat, lon, KIND_HAMLET); break;
        default: break;
      }
    }

    /** Znak początku lub końca miejscowości (D-42, D-43). */
    public void addSign(double lat, double lon) { signs.add(lat, lon, KIND_SIGN); }

    public long buildings() { return buildings; }

    /** Zbudowane z kafelków okno sąsiedztwa; zwraca sumę z kwadratu o promieniu r komórek. */
    private static int win(char[] g, int cols, int rows, int cx, int cy, int r) {
      int y0 = Math.max(0, cy - r), y1 = Math.min(rows - 1, cy + r), x0 = Math.max(0, cx - r), x1 = Math.min(cols - 1, cx + r);
      int s = 0;
      for (int y = y0; y <= y1; y++) {
        int base = y * cols;
        for (int x = x0; x <= x1; x++) s += g[base + x];
      }
      return s;
    }

    int buildingsWithin(int cx, int cy, int r) { return win(bld, cols, rows, cx, cy, r); }

    int coarseBuildings(int cx, int cy) {
      int bx = cx / BLOCK, by = cy / BLOCK;
      return win(coarse, bcols, brows, bx, by, 1);
    }
  }

  /** Mały indeks punktów po kafelkach 0,02 x 0,02 stopnia: pytanie "czy w promieniu jest punkt danego rodzaju". */
  static final class Points {
    private static final int CELL = 20000;
    private double[] la = new double[256], lo = new double[256];
    private byte[] kind = new byte[256];
    private int n;
    private Map<Long, int[]> idx;

    void add(double lat, double lon, int k) {
      if (n == la.length) {
        la = java.util.Arrays.copyOf(la, n * 2); lo = java.util.Arrays.copyOf(lo, n * 2); kind = java.util.Arrays.copyOf(kind, n * 2);
      }
      la[n] = lat; lo[n] = lon; kind[n] = (byte) k; n++;
      idx = null;
    }

    private static long key(int y, int x) { return ((long) y << 32) ^ (x & 0xFFFFFFFFL); }

    private void build() {
      Map<Long, List<Integer>> tmp = new HashMap<Long, List<Integer>>();
      for (int i = 0; i < n; i++) {
        long k = key((int) Math.floor(la[i] * 1e6 / CELL), (int) Math.floor(lo[i] * 1e6 / CELL));
        List<Integer> l = tmp.get(k);
        if (l == null) { l = new ArrayList<Integer>(2); tmp.put(k, l); }
        l.add(i);
      }
      Map<Long, int[]> m = new HashMap<Long, int[]>(tmp.size() * 2);
      for (Map.Entry<Long, List<Integer>> e : tmp.entrySet()) {
        int[] a = new int[e.getValue().size()];
        for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
        m.put(e.getKey(), a);
      }
      idx = m;
    }

    /** Czy w promieniu meters jest punkt, którego rodzaj mieści się w masce (promień nie większy niż ok. 1,3 km). */
    boolean within(double lat, double lon, int mask, double meters) {
      if (n == 0) return false;
      if (idx == null) build();
      int cy = (int) Math.floor(lat * 1e6 / CELL), cx = (int) Math.floor(lon * 1e6 / CELL);
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          int[] a = idx.get(key(cy + dy, cx + dx));
          if (a == null) continue;
          for (int i : a) {
            if ((kind[i] & mask) == 0) continue;
            if (distM(lat, lon, la[i], lo[i]) <= meters) return true;
          }
        }
      }
      return false;
    }
  }

  static double distM(double la1, double lo1, double la2, double lo2) {
    double dy = (la2 - la1) * 111194.9, dx = (lo2 - lo1) * 111194.9 * Math.cos(Math.toRadians((la1 + la2) / 2));
    return Math.sqrt(dx * dx + dy * dy);
  }

  private static boolean eligible(Road r) {
    String h = r.highway;
    if (!("secondary".equals(h) || "tertiary".equals(h) || "unclassified".equals(h))) return false;
    return r.source == Road.Source.NONE && r.limit == Maxspeed.UNKNOWN && r.limitFwd == Maxspeed.UNKNOWN && r.limitBwd == Maxspeed.UNKNOWN
        && r.points() >= 2 && r.id >= 0 && r.id < (1L << PART_SHIFT);
  }

  /** Dodaje do siatki długość drogi (w połówkach metra, co 15 m środek odcinka) z jawnym limitem 50 albo 90. */
  private static void raster(Context c, char[] g, Road r) {
    for (int i = 1; i < r.points(); i++) {
      double len = distM(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i]);
      int n = Math.max(1, (int) Math.ceil(len / 15.0));
      int units = (int) Math.round(len / n * 2);
      for (int k = 0; k < n; k++) {
        double t = (k + 0.5) / n;
        int cx = c.cellX(r.lon[i - 1] + (r.lon[i] - r.lon[i - 1]) * t), cy = c.cellY(r.lat[i - 1] + (r.lat[i] - r.lat[i - 1]) * t);
        if (!c.inside(cx, cy)) continue;
        int j = cy * c.cols + cx;
        g[j] = (char) Math.min(Character.MAX_VALUE, g[j] + units);
      }
    }
  }

  /**
   * Zwraca nową listę dróg: drogi kandydatki (secondary, tertiary, unclassified bez żadnego limitu) mogą zostać pocięte na odcinki
   * z szacunkiem 50 albo 90, reszta bez zmian. ctx może być null (wtedy lista wraca bez zmian). res może być null.
   */
  public static List<Road> apply(List<Road> roads, Context ctx, Result res) {
    return apply(roads, ctx, res, new Params());
  }

  public static List<Road> apply(List<Road> roads, Context ctx, Result res, Params prm) {
    if (res == null) res = new Result();
    if (ctx == null || ctx.buildings == 0) return roads;
    char[] s50 = new char[ctx.rows * ctx.cols], s90 = new char[ctx.rows * ctx.cols];
    for (Road r : roads) {
      if (r.source == Road.Source.SIGN && r.limit == 50) raster(ctx, s50, r);
      else if (r.source == Road.Source.SIGN && r.limit == 90) raster(ctx, s90, r);
    }
    List<Road> out = new ArrayList<Road>(roads.size() + roads.size() / 20);
    for (Road r : roads) {
      if (!eligible(r)) { out.add(r); continue; }
      process(r, ctx, s50, s90, out, res, prm);
    }
    return out;
  }

  private static void process(Road r, Context ctx, char[] s50, char[] s90, List<Road> out, Result res, Params prm) {
    int n = r.points();
    double[] cum = new double[n];
    for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + distM(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i]);
    double total = cum[n - 1];
    res.candidates++;
    res.candidateKm += total / 1000.0;
    if (total < 10) { out.add(r); return; }

    double step = Math.max(STEP_M, total / 400.0);
    int ns = (int) Math.ceil(total / step) + 1;
    double[] pos = new double[ns];
    byte[] label = new byte[ns];
    int seg = 1;
    boolean ruralOk = prm.rural && ("secondary".equals(r.highway) || "tertiary".equals(r.highway)) && r.oneway == 0;
    int urbanMin = "unclassified".equals(r.highway) ? prm.urbanMinUnclassified : prm.urbanMin;
    for (int k = 0; k < ns; k++) {
      double d = k == ns - 1 ? total : k * step;
      pos[k] = d;
      while (seg < n - 1 && cum[seg] < d) seg++;
      double span = cum[seg] - cum[seg - 1];
      double t = span <= 0 ? 0 : Math.min(1, Math.max(0, (d - cum[seg - 1]) / span));
      double lat = r.lat[seg - 1] + (r.lat[seg] - r.lat[seg - 1]) * t, lon = r.lon[seg - 1] + (r.lon[seg] - r.lon[seg - 1]) * t;
      label[k] = classify(ctx, s50, s90, lat, lon, urbanMin, ruralOk, prm);
    }

    // jedna odstająca próbka nie wystarcza: etykieta musi zgadzać się z obiema sąsiednimi
    byte[] fin = new byte[ns];
    for (int k = 0; k < ns; k++) {
      boolean prev = k == 0 || label[k - 1] == label[k], next = k == ns - 1 || label[k + 1] == label[k];
      fin[k] = prev && next ? label[k] : NONE;
    }
    // odcinki z etykietą: ciągi tej samej etykiety od pierwszej do ostatniej próbki; krótkie odrzucamy (chyba że to cała droga)
    List<double[]> labeled = new ArrayList<double[]>();   // {od, do, etykieta}
    int k = 0;
    while (k < ns) {
      int a = k;
      while (k < ns && fin[k] == fin[a]) k++;
      int b = k - 1;
      if (fin[a] == NONE) continue;
      boolean whole = a == 0 && b == ns - 1;
      double from = a == 0 ? 0 : pos[a], to = b == ns - 1 ? total : pos[b];
      if (!whole && to - from < prm.minRunM) continue;
      labeled.add(new double[] {from, to, fin[a]});
    }
    // pasy pomiędzy nimi (i na końcach drogi) zostają bez limitu
    List<double[]> merged = new ArrayList<double[]>();
    double at = 0;
    for (double[] p : labeled) {
      if (p[0] > at) merged.add(new double[] {at, p[0], NONE});
      merged.add(p);
      at = p[1];
    }
    if (!labeled.isEmpty() && at < total) merged.add(new double[] {at, total, NONE});
    boolean any = false;
    for (double[] p : merged) if (p[2] != NONE) any = true;
    if (!any) { out.add(r); return; }

    if (merged.size() == 1) {
      byte lab = (byte) merged.get(0)[2];
      out.add(new Road(r.id, r.highway, r.oneway, lab == URBAN ? 50 : 90, Road.Source.ESTIMATE, r.limitFwd, r.limitBwd, r.lat, r.lon));
      count(res, lab, total);
      return;
    }
    res.splitRoads++;
    int idx = 0;
    for (double[] p : merged) {
      byte lab = (byte) p[2];
      double[][] g = slice(r, cum, p[0], p[1]);
      long id = idx == 0 ? r.id : r.id + ((long) idx << PART_SHIFT);
      idx++;
      double[] la = g[0], lo = g[1];
      if (lab == NONE) out.add(new Road(id, r.highway, r.oneway, Maxspeed.UNKNOWN, Road.Source.NONE, r.limitFwd, r.limitBwd, la, lo));
      else out.add(new Road(id, r.highway, r.oneway, lab == URBAN ? 50 : 90, Road.Source.ESTIMATE, r.limitFwd, r.limitBwd, la, lo));
      count(res, lab, p[1] - p[0]);
    }
  }

  private static void count(Result res, byte lab, double meters) {
    if (lab == URBAN) { res.urbanParts++; res.urbanKm += meters / 1000.0; }
    else if (lab == RURAL) { res.ruralParts++; res.ruralKm += meters / 1000.0; }
  }

  private static byte classify(Context c, char[] s50, char[] s90, double lat, double lon, int urbanMin, boolean ruralOk, Params prm) {
    int cx = c.cellX(lon), cy = c.cellY(lat);
    if (!c.inside(cx, cy)) return NONE;
    if (c.buildingsWithin(cx, cy, 1) >= urbanMin) {
      return !prm.veto || Context.win(s90, c.cols, c.rows, cx, cy, 2) == 0 ? URBAN : NONE;
    }
    if (!ruralOk) return NONE;
    if (c.buildingsWithin(cx, cy, 3) > prm.ruralMax) return NONE;
    if (prm.veto && Context.win(s50, c.cols, c.rows, cx, cy, 2) != 0) return NONE;
    if (c.coarseBuildings(cx, cy) < prm.coverageMin) return NONE;
    if (c.settlements.within(lat, lon, KIND_SETTLEMENT, prm.settlementGuardM)) return NONE;
    if (c.settlements.within(lat, lon, KIND_HAMLET, prm.hamletGuardM)) return NONE;
    if (c.signs.within(lat, lon, KIND_SIGN, prm.signGuardM)) return NONE;
    return RURAL;
  }

  /** Fragment łamanej od pozycji a do b (w metrach od początku): punkty skrajne są interpolowane. */
  static double[][] slice(Road r, double[] cum, double a, double b) {
    int n = r.points();
    List<double[]> pts = new ArrayList<double[]>();
    pts.add(at(r, cum, a));
    for (int i = 1; i < n - 1; i++) if (cum[i] > a + 0.5 && cum[i] < b - 0.5) pts.add(new double[] {r.lat[i], r.lon[i]});
    pts.add(at(r, cum, b));
    double[] la = new double[pts.size()], lo = new double[pts.size()];
    for (int i = 0; i < la.length; i++) { la[i] = pts.get(i)[0]; lo[i] = pts.get(i)[1]; }
    return new double[][] {la, lo};
  }

  private static double[] at(Road r, double[] cum, double d) {
    int n = r.points();
    if (d <= 0) return new double[] {r.lat[0], r.lon[0]};
    if (d >= cum[n - 1]) return new double[] {r.lat[n - 1], r.lon[n - 1]};
    int s = 1;
    while (s < n - 1 && cum[s] < d) s++;
    double span = cum[s] - cum[s - 1];
    double t = span <= 0 ? 0 : (d - cum[s - 1]) / span;
    return new double[] {r.lat[s - 1] + (r.lat[s] - r.lat[s - 1]) * t, r.lon[s - 1] + (r.lon[s] - r.lon[s - 1]) * t};
  }
}
