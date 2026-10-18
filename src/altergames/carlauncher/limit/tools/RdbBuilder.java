package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.PackGrid;
import altergames.carlauncher.limit.core.Road;
import altergames.carlauncher.limit.core.RoadDb;
import altergames.carlauncher.limit.core.RoadResolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Budowa bazy dróg z pliku .osm.pbf. Używane na komputerze i w automacie GitHub Actions, nie w aplikacji.
 * Wynik to katalog małych paczek p_X_Y.rdb (po 5 x 5 kafelków) i manifest.json z ich rozmiarami i sumami kontrolnymi.
 * Użycie: RdbBuilder wejście.osm.pbf katalog-wyjściowy [znacznik-czasu-OSM-w-sekundach]
 */
public final class RdbBuilder {
  public static final class Stats {
    public int waysRead, roads, tiles, tileRoadEntries, skippedNoGeometry, packs;
    public long bytes;
    @Override public String toString() {
      return "dróg w pliku: " + waysRead + ", zapisanych: " + roads + ", pominiętych bez geometrii: " + skippedNoGeometry
          + ", kafelków: " + tiles + ", paczek: " + packs + ", wpisów dróg w kafelkach: " + tileRoadEntries + ", rozmiar: " + bytes + " B";
    }
  }

  static final Set<String> WANTED = new HashSet<String>(Arrays.asList("highway", "maxspeed", "maxspeed:forward", "maxspeed:backward",
      "maxspeed:type", "source:maxspeed", "zone:maxspeed", "oneway", "junction", "motorroad", "area"));

  private static final class WayRec {
    final long id; final Map<String, String> tags; final long[] refs;
    WayRec(long id, Map<String, String> tags, long[] refs) { this.id = id; this.tags = tags; this.refs = refs; }
  }

  /** Czyta plik .osm.pbf i zwraca drogi z geometrią oraz rozpoznanymi limitami. */
  public static List<Road> readRoads(File pbf, Stats st) throws IOException {
    Set<String> hw = new HashSet<String>(Arrays.asList(RoadDb.HIGHWAYS));

    // przebieg 1: drogi
    final List<WayRec> ways = new ArrayList<WayRec>();
    PbfReader.readWays(pbf, WANTED, hw, new PbfReader.WaySink() {
      public void way(long id, Map<String, String> tags, long[] refs) { ways.add(new WayRec(id, tags, refs)); }
    });
    st.waysRead = ways.size();

    // potrzebne punkty: posortowane unikalne identyfikatory
    long total = 0;
    for (WayRec w : ways) total += w.refs.length;
    if (total > 400_000_000L) throw new IOException("za dużo punktów dla jednego pliku");
    long[] all = new long[(int) total];
    int n = 0;
    for (WayRec w : ways) { System.arraycopy(w.refs, 0, all, n, w.refs.length); n += w.refs.length; }
    Arrays.sort(all);
    int u = 0;
    for (int i = 0; i < all.length; i++) if (i == 0 || all[i] != all[i - 1]) all[u++] = all[i];
    final long[] needed = Arrays.copyOf(all, u);
    all = null;
    final int[] la = new int[u], lo = new int[u];
    Arrays.fill(la, Integer.MIN_VALUE);

    // przebieg 2: współrzędne punktów (kolejność w pliku dowolna)
    PbfReader.readNodes(pbf, new PbfReader.NodeSink() {
      public void node(long id, double lat, double lon) {
        int i = Arrays.binarySearch(needed, id);
        if (i >= 0) { la[i] = (int) Math.round(lat * 1e6); lo[i] = (int) Math.round(lon * 1e6); }
      }
    });

    List<Road> roads = new ArrayList<Road>();
    for (WayRec w : ways) {
      double[] rla = new double[w.refs.length], rlo = new double[w.refs.length];
      int k = 0;
      for (long ref : w.refs) {
        int i = Arrays.binarySearch(needed, ref);
        if (i < 0 || la[i] == Integer.MIN_VALUE) continue;
        rla[k] = la[i] / 1e6; rlo[k] = lo[i] / 1e6; k++;
      }
      if (k < 2) { st.skippedNoGeometry++; continue; }
      if (k != rla.length) { rla = Arrays.copyOf(rla, k); rlo = Arrays.copyOf(rlo, k); }
      Road r = RoadResolver.fromTags(w.id, w.tags, rla, rlo);
      if (r != null) roads.add(r);
    }
    st.roads = roads.size();
    return roads;
  }

  /** Cały przebieg: PBF -> paczki + manifest w katalogu wyjściowym. */
  public static Stats buildPacks(File pbf, File outDir, long osmTimeSec, long buildTimeSec) throws IOException {
    Stats st = new Stats();
    List<Road> roads = readRoads(pbf, st);
    writePacks(roads, outDir, osmTimeSec, buildTimeSec, st);
    return st;
  }

  /** Zapis paczek i manifestu z gotowej listy dróg (także do testów). */
  public static void writePacks(List<Road> roads, File outDir, long osmTimeSec, long buildTimeSec, Stats st) throws IOException {
    if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("nie można utworzyć katalogu " + outDir);
    TreeMap<Long, List<Road>> tiles = groupByTile(roads);
    // kafelki -> paczki
    TreeMap<Long, TreeMap<Long, List<Road>>> packs = new TreeMap<Long, TreeMap<Long, List<Road>>>();
    for (Map.Entry<Long, List<Road>> e : tiles.entrySet()) {
      int tx = tileXOfKey(e.getKey()), ty = tileYOfKey(e.getKey());
      long pk = PackGrid.key(PackGrid.packOfTile(tx), PackGrid.packOfTile(ty));
      TreeMap<Long, List<Road>> p = packs.get(pk);
      if (p == null) { p = new TreeMap<Long, List<Road>>(); packs.put(pk, p); }
      p.put(e.getKey(), e.getValue());
    }
    StringBuilder man = new StringBuilder(packs.size() * 28 + 256);
    man.append("{\"v\":1,\"built\":").append(buildTimeSec).append(",\"osm\":").append(osmTimeSec)
       .append(",\"tileLat\":").append(PackGrid.TILE_LAT_MICRO).append(",\"tileLon\":").append(PackGrid.TILE_LON_MICRO)
       .append(",\"packTiles\":").append(PackGrid.PACK_TILES).append(",\"packs\":[");
    long bytes = 0, entries = 0;
    boolean first = true;
    for (Map.Entry<Long, TreeMap<Long, List<Road>>> e : packs.entrySet()) {
      int px = tileXOfKey(e.getKey()), py = tileYOfKey(e.getKey());
      File f = new File(outDir, PackGrid.name(px, py));
      // czasy w nagłówku paczki zerowe: ta sama zawartość drogowa daje ten sam plik, więc aplikacje pobierają
      // ponownie tylko paczki, w których coś się naprawdę zmieniło (czas budowy jest w manifeście)
      writeFile(f, e.getValue(), 0L, 0L);
      long crc = crcOf(f);
      bytes += f.length();
      for (List<Road> l : e.getValue().values()) entries += l.size();
      if (!first) man.append(',');
      first = false;
      man.append('[').append(px).append(',').append(py).append(',').append(f.length()).append(',').append(crc).append(']');
    }
    man.append("]}");
    File mf = new File(outDir, "manifest.json");
    File tmp = new File(outDir, "manifest.json.tmp");
    FileOutputStream fo = new FileOutputStream(tmp);
    try { fo.write(man.toString().getBytes(StandardCharsets.UTF_8)); fo.getFD().sync(); } finally { fo.close(); }
    if (mf.exists() && !mf.delete()) throw new IOException("nie można nadpisać manifestu");
    if (!tmp.renameTo(mf)) throw new IOException("nie można zapisać manifestu");
    if (st != null) { st.tiles = tiles.size(); st.packs = packs.size(); st.bytes = bytes; st.tileRoadEntries = (int) entries; }
  }

  /** Zapis jednego pliku ze wszystkimi kafelkami (do testów i do narzędzi diagnostycznych). */
  public static void write(List<Road> roads, File out, long osmTimeSec, long buildTimeSec, Stats st) throws IOException {
    TreeMap<Long, List<Road>> tiles = groupByTile(roads);
    writeFile(out, tiles, osmTimeSec, buildTimeSec);
    if (st != null) {
      long entries = 0;
      for (List<Road> l : tiles.values()) entries += l.size();
      st.tiles = tiles.size(); st.tileRoadEntries = (int) entries; st.bytes = out.length(); st.packs = 1;
    }
  }

  static TreeMap<Long, List<Road>> groupByTile(List<Road> roads) {
    TreeMap<Long, List<Road>> tiles = new TreeMap<Long, List<Road>>();
    for (Road r : roads) {
      for (long key : tilesOf(r)) {
        List<Road> l = tiles.get(key);
        if (l == null) { l = new ArrayList<Road>(); tiles.put(key, l); }
        l.add(r);
      }
    }
    return tiles;
  }

  static int tileXOfKey(long key) { return (int) (key & 0xFFFFFFFFL); }
  static int tileYOfKey(long key) { return (int) (key >> 32); }

  private static void writeFile(File out, TreeMap<Long, List<Road>> tiles, long osmTimeSec, long buildTimeSec) throws IOException {
    List<byte[]> blocks = new ArrayList<byte[]>();
    List<long[]> meta = new ArrayList<long[]>();   // tx, ty, crc
    for (Map.Entry<Long, List<Road>> e : tiles.entrySet()) {
      int tx = tileXOfKey(e.getKey()), ty = tileYOfKey(e.getKey());
      byte[] comp = compress(encodeTile(e.getValue(), tx, ty));
      CRC32 crc = new CRC32();
      crc.update(comp, 0, comp.length);
      blocks.add(comp);
      meta.add(new long[] {tx, ty, crc.getValue()});
    }
    long off = RoadDb.HEADER_SIZE;
    long[] offs = new long[blocks.size()];
    for (int i = 0; i < blocks.size(); i++) { offs[i] = off; off += blocks.get(i).length; }
    long indexOff = off;

    File tmp = new File(out.getAbsolutePath() + ".tmp");
    FileOutputStream fo = new FileOutputStream(tmp);
    try {
      ByteBuffer h = ByteBuffer.allocate(RoadDb.HEADER_SIZE);
      h.putInt(RoadDb.MAGIC);
      h.put((byte) RoadDb.VERSION);
      h.position(8);
      h.putInt(PackGrid.TILE_LAT_MICRO).putInt(PackGrid.TILE_LON_MICRO).putLong(buildTimeSec).putLong(osmTimeSec)
       .putInt(blocks.size()).putLong(indexOff);
      fo.write(h.array());
      for (byte[] b : blocks) fo.write(b);
      ByteBuffer idx = ByteBuffer.allocate(blocks.size() * RoadDb.INDEX_ENTRY + 4);
      for (int i = 0; i < blocks.size(); i++) {
        long[] m = meta.get(i);
        idx.putInt((int) m[0]).putInt((int) m[1]).putLong(offs[i]).putInt(blocks.get(i).length).putInt((int) m[2]);
      }
      idx.putInt(RoadDb.END_MAGIC);
      fo.write(idx.array());
      fo.getFD().sync();
    } finally {
      fo.close();
    }
    if (out.exists() && !out.delete()) throw new IOException("nie można nadpisać " + out);
    if (!tmp.renameTo(out)) throw new IOException("nie można zmienić nazwy " + tmp);
  }

  public static long crcOf(File f) throws IOException {
    CRC32 crc = new CRC32();
    FileInputStream in = new FileInputStream(f);
    try {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
    } finally {
      in.close();
    }
    return crc.getValue();
  }

  /** Kafelki dotknięte przez drogę: punkty oraz próbki wzdłuż długich odcinków. */
  static Set<Long> tilesOf(Road r) {
    Set<Long> s = new HashSet<Long>();
    for (int i = 0; i < r.points(); i++) {
      s.add(RoadDb.key(PackGrid.tileX(r.lon[i]), PackGrid.tileY(r.lat[i])));
      if (i > 0) {
        double dLat = r.lat[i] - r.lat[i - 1], dLon = r.lon[i] - r.lon[i - 1];
        int steps = (int) Math.ceil(Math.max(Math.abs(dLat) * 1e6 / (PackGrid.TILE_LAT_MICRO / 2.0),
            Math.abs(dLon) * 1e6 / (PackGrid.TILE_LON_MICRO / 2.0)));
        steps = Math.min(steps, 2000);
        for (int k = 1; k < steps; k++) {
          double t = k / (double) steps;
          s.add(RoadDb.key(PackGrid.tileX(r.lon[i - 1] + dLon * t), PackGrid.tileY(r.lat[i - 1] + dLat * t)));
        }
      }
    }
    return s;
  }

  private static byte[] encodeTile(List<Road> roads, int tx, int ty) {
    ByteArrayOutputStream o = new ByteArrayOutputStream(roads.size() * 60 + 16);
    varint(o, roads.size());
    long originLat = (long) ty * PackGrid.TILE_LAT_MICRO, originLon = (long) tx * PackGrid.TILE_LON_MICRO;
    for (Road r : roads) {
      varint(o, r.id);
      int hw = Arrays.asList(RoadDb.HIGHWAYS).indexOf(r.highway);
      if (hw < 0) throw new IllegalStateException("nieznany typ drogi " + r.highway);
      int ow = r.oneway == 1 ? 1 : r.oneway == -1 ? 2 : 0;
      o.write(hw | (ow << 4) | (r.source.ordinal() << 6));
      o.write(r.limit > 0 ? r.limit / 5 : 0);
      o.write(r.limitFwd > 0 ? r.limitFwd / 5 : 0);
      o.write(r.limitBwd > 0 ? r.limitBwd / 5 : 0);
      varint(o, r.points());
      long pl = Math.round(r.lat[0] * 1e6), po = Math.round(r.lon[0] * 1e6);
      varint(o, zig(pl - originLat)); varint(o, zig(po - originLon));
      for (int k = 1; k < r.points(); k++) {
        long cl = Math.round(r.lat[k] * 1e6), co = Math.round(r.lon[k] * 1e6);
        varint(o, zig(cl - pl)); varint(o, zig(co - po));
        pl = cl; po = co;
      }
    }
    return o.toByteArray();
  }

  private static byte[] compress(byte[] raw) {
    Deflater d = new Deflater(9);
    try {
      d.setInput(raw);
      d.finish();
      ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length / 2 + 16);
      byte[] buf = new byte[64 * 1024];
      while (!d.finished()) { int n = d.deflate(buf); o.write(buf, 0, n); }
      return o.toByteArray();
    } finally {
      d.end();
    }
  }

  static long zig(long v) { return (v << 1) ^ (v >> 63); }

  static void varint(ByteArrayOutputStream o, long v) {
    while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
    o.write((int) v);
  }

  public static void main(String[] a) throws Exception {
    if (a.length < 2) {
      System.err.println("użycie: RdbBuilder wejście.osm.pbf katalog-wyjściowy [minimalna-liczba-paczek]");
      System.exit(2);
    }
    File pbf = new File(a[0]);
    int minPacks = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    long osm = PbfReader.headerTimestamp(pbf);
    long t0 = System.currentTimeMillis();
    Stats s = buildPacks(pbf, new File(a[1]), osm, System.currentTimeMillis() / 1000);
    System.out.println(s + " (" + (System.currentTimeMillis() - t0) / 1000 + " s), czas danych OSM: " + osm);
    if (s.packs < minPacks) {
      System.err.println("BŁĄD: za mało paczek (" + s.packs + " < " + minPacks + "), plik wejściowy wygląda na niepełny");
      System.exit(3);
    }
  }
}
