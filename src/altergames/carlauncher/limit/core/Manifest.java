package altergames.carlauncher.limit.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manifest bazy: czas budowy i spis paczek z rozmiarami oraz sumami kontrolnymi. Pobierany rzadko (co kilkanaście godzin),
 * dzięki niemu aplikacja wie, które paczki istnieją i które u niej są nieaktualne. Walidowany surowo: byle jaki plik
 * z internetu (albo uszkodzony) nie może zepsuć działania, zostaje wtedy poprzedni manifest.
 */
public final class Manifest {
  public static final int MAX_PACKS = 60_000;
  public static final long MAX_PACK_BYTES = 12_000_000L;

  public static final class Entry {
    public final long size, crc;
    /** Pierwsze 128 bitów SHA-256 paczki (32 znaki szesnastkowe) albo null w spisach bez tej sumy. */
    public final String sha;
    Entry(long size, long crc, String sha) { this.size = size; this.crc = crc; this.sha = sha; }
  }

  public final long builtSec, osmSec;
  /** Wersja formatu paczek (1: same drogi, 2: także radary) i liczba radarów (v2). */
  public final int version, cameras;
  private final Map<Long, Entry> packs;

  private Manifest(int version, long builtSec, long osmSec, int cameras, Map<Long, Entry> packs) {
    this.version = version; this.builtSec = builtSec; this.osmSec = osmSec; this.cameras = cameras; this.packs = packs;
  }

  public int size() { return packs.size(); }

  public Entry get(int px, int py) { return packs.get(PackGrid.key(px, py)); }

  public Entry get(long key) { return packs.get(key); }

  /** Klucze wszystkich paczek ze spisu (do przeglądania, nie do zmieniania). */
  public java.util.Set<Long> keys() { return java.util.Collections.unmodifiableSet(packs.keySet()); }

  /** Zgłasza IllegalArgumentException przy każdej niezgodności. */
  public static Manifest parse(String text) {
    Object root;
    try {
      root = Json.parse(text);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("manifest: zły JSON");
    }
    Map<String, Object> m = Json.obj(root);
    if (m == null) throw new IllegalArgumentException("manifest: to nie obiekt");
    long v = integer(m.get("v"), -1);
    if (v < RoadDb.MIN_VERSION || v > RoadDb.VERSION) throw new IllegalArgumentException("manifest: nieobsługiwana wersja " + v);
    if (integer(m.get("tileLat"), -1) != PackGrid.TILE_LAT_MICRO || integer(m.get("tileLon"), -1) != PackGrid.TILE_LON_MICRO
        || integer(m.get("packTiles"), -1) != PackGrid.PACK_TILES) {
      throw new IllegalArgumentException("manifest: inna siatka niż w aplikacji");
    }
    long built = integer(m.get("built"), -1), osm = integer(m.get("osm"), -1);
    if (built < 0 || osm < 0) throw new IllegalArgumentException("manifest: zły czas");
    List<Object> arr = Json.arr(m.get("packs"));
    if (arr == null || arr.size() > MAX_PACKS) throw new IllegalArgumentException("manifest: zła lista paczek");
    Map<Long, Entry> out = new HashMap<Long, Entry>(arr.size() * 2);
    for (Object o : arr) {
      List<Object> p = Json.arr(o);
      if (p == null || (p.size() != 4 && p.size() != 5)) throw new IllegalArgumentException("manifest: zły wpis");
      String sha = null;
      if (p.size() == 5) {
        Object so = p.get(4);
        if (!(so instanceof String) || !((String) so).matches("[0-9a-f]{32}")) throw new IllegalArgumentException("manifest: zła suma SHA-256");
        sha = (String) so;
      }
      long px = integer(p.get(0), Long.MIN_VALUE), py = integer(p.get(1), Long.MIN_VALUE);
      long size = integer(p.get(2), -1), crc = integer(p.get(3), -1);
      if (px < -3000 || px > 3000 || py < -1500 || py > 1500) throw new IllegalArgumentException("manifest: współrzędne poza zakresem");
      if (size <= 0 || size > MAX_PACK_BYTES) throw new IllegalArgumentException("manifest: zły rozmiar");
      if (crc < 0 || crc > 0xFFFFFFFFL) throw new IllegalArgumentException("manifest: zła suma");
      out.put(PackGrid.key((int) px, (int) py), new Entry(size, crc, sha));
    }
    long cams = integer(m.get("cams"), 0);
    return new Manifest((int) v, built, osm, (int) Math.max(0, Math.min(cams, 1_000_000)), out);
  }

  /** Liczba całkowita zapisana jako liczba JSON; inaczej def. */
  private static long integer(Object o, long def) {
    if (!(o instanceof Double)) return def;
    double d = (Double) o;
    if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d) || Math.abs(d) > 9e15) return def;
    return (long) d;
  }
}
