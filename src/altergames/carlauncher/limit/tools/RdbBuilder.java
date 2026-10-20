package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.Camera;
import altergames.carlauncher.limit.core.OsmCameras;
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
 * Budowa bazy dróg i radarów z pliku .osm.pbf (i opcjonalnie wykazu ITD). Używane na komputerze i w automacie GitHub Actions,
 * nie w aplikacji. Wynik to katalog małych paczek p_X_Y.rdb (po 5 x 5 kafelków) i manifest.json z ich rozmiarami i sumami kontrolnymi.
 * Użycie: RdbBuilder [--itd=wykaz.csv] [--no-neighbors] [--urban] wejście.osm.pbf katalog-wyjściowy [minimalna-liczba-paczek]
 * Domyślnie paczki zawierają limity zapisane w OSM i oznaczone szacunki (z typu drogi i uzupełnione z sąsiadów); aplikacja ma przełącznik, czy je
 * pokazywać. --no-neighbors wyłącza uzupełnianie z sąsiednich odcinków (LimitInference), --urban włącza rozpoznawanie terenu zabudowanego
 * z gęstości budynków (UrbanInference, domyślnie wyłączone).
 */
public final class RdbBuilder {
  public static final class Stats {
    public int waysRead, roads, tiles, tileRoadEntries, skippedNoGeometry, packs;
    public int osmCameras, itdRows, cameras, inferredLimits, cameraTiles;
    public long bytes, buildings;
    /** Rozpoznanie terenu zabudowanego (UrbanInference): odcinki z szacunkiem 50 i 90 oraz podzielone drogi. */
    public UrbanInference.Result urban = new UrbanInference.Result();
    @Override public String toString() {
      return "dróg w pliku: " + waysRead + ", zapisanych: " + roads + ", pominiętych bez geometrii: " + skippedNoGeometry
          + ", uzupełnionych limitów: " + inferredLimits + ", budynków: " + buildings + ", " + urban
          + ", radarów OSM: " + osmCameras + ", wierszy ITD: " + itdRows + ", radarów razem: " + cameras
          + ", kafelków: " + tiles + ", paczek: " + packs + ", wpisów dróg w kafelkach: " + tileRoadEntries + ", rozmiar: " + bytes + " B";
    }
  }

  /** Drogi i radary wczytane z plików źródłowych. */
  public static final class Data {
    public final List<Road> roads;
    public final List<Camera> cameras;
    /** Siatka budynków, miejscowości i znaków granic użyta do rozpoznania terenu zabudowanego (null, gdy nie powstała); do diagnostyki. */
    public final UrbanInference.Context urban;
    Data(List<Road> roads, List<Camera> cameras, UrbanInference.Context urban) { this.roads = roads; this.cameras = cameras; this.urban = urban; }
  }

  static final Set<String> WANTED = new HashSet<String>(Arrays.asList("highway", "maxspeed", "maxspeed:forward", "maxspeed:backward",
      "maxspeed:type", "source:maxspeed", "zone:maxspeed", "oneway", "junction", "motorroad", "area", "name", "ref"));

  private static final Set<String> RELATION_TYPES = new HashSet<String>(Arrays.asList("enforcement"));
  private static final Set<String> RELATION_KEYS = new HashSet<String>(Arrays.asList("type", "enforcement", "maxspeed", "length"));
  private static final PbfReader.TagFilter CAMERA_NODES = new PbfReader.TagFilter("highway=speed_camera");
  /** Punkty z tagami dla rozpoznawania terenu zabudowanego: radary, miejscowości (place) i znaki granic miejscowości (D-42, D-43). */
  private static final PbfReader.TagFilter TAGGED_NODES = new PbfReader.TagFilter("highway=speed_camera", "place", "city_limit",
      "traffic_sign=city_limit", "traffic_sign=PL:D-42", "traffic_sign=PL:D-43");

  private static final class WayRec {
    final long id; final Map<String, String> tags; final long[] refs;
    WayRec(long id, Map<String, String> tags, long[] refs) { this.id = id; this.tags = tags; this.refs = refs; }
  }

  /** Czyta plik .osm.pbf i zwraca drogi z geometrią oraz rozpoznanymi limitami (bez radarów). */
  public static List<Road> readRoads(File pbf, Stats st) throws IOException {
    return read(pbf, null, st).roads;
  }

  /**
   * Czyta plik .osm.pbf i opcjonalny wykaz ITD: drogi z limitami zapisanymi w OSM oraz radary.
   * Szacunki są w danych zawsze oznaczone (Road.Source.ESTIMATE), a o ich pokazywaniu decyduje przełącznik w aplikacji (decyzja Kamila
   * 2026-10-20). Domyślnie jest WŁĄCZONE uzupełnianie z sąsiadów (LimitInference, bezpieczne); rozpoznawanie terenu zabudowanego z budynków
   * (UrbanInference, sekcja 4.5c dokumentu przekazania) jest WYŁĄCZONE do czasu decyzji po zmierzeniu trafności i włącza je tylko flaga.
   */
  public static Data read(File pbf, File itdCsv, Stats st) throws IOException {
    return readImpl(pbf, itdCsv, st, false, false, true);
  }

  /** Jak wyżej; urbanInference = true czyta też budynki i wnioskuje 50 / 90 na drogach bez limitu (opcja, domyślnie wyłączona). */
  public static Data read(File pbf, File itdCsv, Stats st, boolean urbanInference) throws IOException {
    return readImpl(pbf, itdCsv, st, urbanInference, urbanInference, true);
  }

  /** Jak wyżej, z osobnymi przełącznikami: neighbors = uzupełnianie limitów z sąsiednich odcinków (LimitInference), urban = jak wyżej. */
  public static Data read(File pbf, File itdCsv, Stats st, boolean neighbors, boolean urban) throws IOException {
    return readImpl(pbf, itdCsv, st, urban, urban, neighbors);
  }

  /** Do pomiarów trafności (UrbanCheck): wczytuje budynki i buduje siatkę (Data.urban), ale nie zmienia dróg. */
  public static Data readUrbanContext(File pbf, File itdCsv, Stats st) throws IOException {
    return readImpl(pbf, itdCsv, st, true, false, false);
  }

  private static Data readImpl(File pbf, File itdCsv, Stats st, final boolean buildings, boolean applyUrban, boolean neighbors) throws IOException {
    Set<String> hw = new HashSet<String>(Arrays.asList(RoadDb.HIGHWAYS));

    // przebieg 1: drogi i relacje typu enforcement
    final List<WayRec> ways = new ArrayList<WayRec>();
    final List<OsmCameras.Rel> rels = new ArrayList<OsmCameras.Rel>();
    final long[][] bref = new long[1][1 << 16];     // pierwsze punkty budynków
    final int[] bn = new int[1];
    PbfReader.readWaysRelationsAndBuildings(pbf, WANTED, hw, new PbfReader.WaySink() {
      public void way(long id, Map<String, String> tags, long[] refs) { ways.add(new WayRec(id, tags, refs)); }
    }, RELATION_TYPES, RELATION_KEYS, new PbfReader.RelationSink() {
      public void relation(long id, Map<String, String> tags, long[] mem, int[] types, String[] roles) {
        rels.add(new OsmCameras.Rel(id, tags, mem, types, roles));
      }
    }, !buildings ? null : new PbfReader.BuildingSink() {
      public void building(long first) {
        if (bn[0] == bref[0].length) bref[0] = Arrays.copyOf(bref[0], bn[0] * 2);
        bref[0][bn[0]++] = first;
      }
    });
    st.waysRead = ways.size();

    // potrzebne punkty: posortowane unikalne identyfikatory (punkty dróg i członkowie relacji będący punktami)
    long total = 0;
    for (WayRec w : ways) total += w.refs.length;
    for (OsmCameras.Rel r : rels) total += r.mem.length;
    total += bn[0];
    if (total > 400_000_000L) throw new IOException("za dużo punktów dla jednego pliku");
    long[] all = new long[(int) total];
    int n = 0;
    for (WayRec w : ways) { System.arraycopy(w.refs, 0, all, n, w.refs.length); n += w.refs.length; }
    System.arraycopy(bref[0], 0, all, n, bn[0]); n += bn[0];
    for (OsmCameras.Rel r : rels) {
      for (int i = 0; i < r.mem.length; i++) if (r.types[i] == PbfReader.MEMBER_NODE) all[n++] = r.mem[i];
    }
    all = Arrays.copyOf(all, n);
    Arrays.sort(all);
    int u = 0;
    for (int i = 0; i < all.length; i++) if (i == 0 || all[i] != all[i - 1]) all[u++] = all[i];
    final long[] needed = Arrays.copyOf(all, u);
    all = null;
    final int[] la = new int[u], lo = new int[u];
    Arrays.fill(la, Integer.MIN_VALUE);

    // przebieg 2: współrzędne punktów (kolejność w pliku dowolna) i radary zmapowane jako punkty
    final List<OsmCameras.TaggedNode> camNodes = new ArrayList<OsmCameras.TaggedNode>();
    final List<Object[]> places = new ArrayList<Object[]>();          // {lat, lon, wartość place}
    final List<double[]> signs = new ArrayList<double[]>();           // {lat, lon}
    PbfReader.readNodes(pbf, new PbfReader.NodeSink() {
      public void node(long id, double lat, double lon) {
        int i = Arrays.binarySearch(needed, id);
        if (i >= 0) { la[i] = (int) Math.round(lat * 1e6); lo[i] = (int) Math.round(lon * 1e6); }
      }
    }, buildings ? TAGGED_NODES : CAMERA_NODES, new PbfReader.TaggedNodeSink() {
      public void node(long id, double lat, double lon, Map<String, String> tags) {
        if ("speed_camera".equals(tags.get("highway"))) camNodes.add(new OsmCameras.TaggedNode(id, lat, lon, tags));
        if (tags.get("place") != null) places.add(new Object[] {lat, lon, tags.get("place")});
        String ts = tags.get("traffic_sign");
        if (tags.get("city_limit") != null || "city_limit".equals(ts) || "PL:D-42".equals(ts) || "PL:D-43".equals(ts)) signs.add(new double[] {lat, lon});
      }
    });

    List<Road> roads = new ArrayList<Road>();
    List<long[]> ends = new ArrayList<long[]>();
    List<String> keys = new ArrayList<String>();
    for (WayRec w : ways) {
      double[] rla = new double[w.refs.length], rlo = new double[w.refs.length];
      int k = 0;
      long firstRef = 0, lastRef = 0;
      for (long ref : w.refs) {
        int i = Arrays.binarySearch(needed, ref);
        if (i < 0 || la[i] == Integer.MIN_VALUE) continue;
        if (k == 0) firstRef = ref;
        lastRef = ref;
        rla[k] = la[i] / 1e6; rlo[k] = lo[i] / 1e6; k++;
      }
      if (k < 2) { st.skippedNoGeometry++; continue; }
      if (k != rla.length) { rla = Arrays.copyOf(rla, k); rlo = Arrays.copyOf(rlo, k); }
      Road r = RoadResolver.fromTags(w.id, w.tags, rla, rlo);
      if (r != null) {
        roads.add(r);
        ends.add(new long[] {firstRef, lastRef});
        keys.add(LimitInference.continuityKey(w.tags));
      }
    }
    ways.clear();
    st.roads = roads.size();

    long[] first = new long[roads.size()], last = new long[roads.size()];
    for (int i = 0; i < roads.size(); i++) { first[i] = ends.get(i)[0]; last[i] = ends.get(i)[1]; }
    ends = null;
    int[] filled = new int[1];
    if (neighbors) roads = LimitInference.apply(roads, first, last, keys.toArray(new String[0]), filled);
    st.inferredLimits = filled[0];
    keys = null;

    // teren zabudowany: gęstość budynków wokół drogi (po uzupełnieniu z sąsiadów, żeby jego wyniki zostały)
    UrbanInference.Context urban = null;
    if (bn[0] > 0 && !roads.isEmpty()) {
      double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
      for (Road r : roads) {
        for (int i = 0; i < r.points(); i++) {
          if (r.lat[i] < minLat) minLat = r.lat[i];
          if (r.lat[i] > maxLat) maxLat = r.lat[i];
          if (r.lon[i] < minLon) minLon = r.lon[i];
          if (r.lon[i] > maxLon) maxLon = r.lon[i];
        }
      }
      urban = UrbanInference.Context.create(minLat, minLon, maxLat, maxLon);
      if (urban != null) {
        for (int bi = 0; bi < bn[0]; bi++) {
          int i = Arrays.binarySearch(needed, bref[0][bi]);
          if (i >= 0 && la[i] != Integer.MIN_VALUE) urban.addBuilding(la[i] / 1e6, lo[i] / 1e6);
        }
        for (Object[] p : places) urban.addPlace((Double) p[0], (Double) p[1], (String) p[2]);
        for (double[] sg : signs) urban.addSign(sg[0], sg[1]);
        st.buildings = urban.buildings();
      }
    }
    bref[0] = null;
    if (applyUrban) roads = UrbanInference.apply(roads, urban, st.urban);

    // radary: OSM + wykaz ITD, ułożone zgodnie z drogami
    List<Camera> itd = null;
    if (itdCsv != null) {
      ItdCsv.Result res = ItdCsv.read(itdCsv);
      st.itdRows = res.rows.size();
      itd = ItdCsv.toCameras(res);
    }
    List<Camera> cameras = CameraBuilder.build(camNodes, rels, new OsmCameras.NodePos() {
      public double[] pos(long id) {
        int i = Arrays.binarySearch(needed, id);
        return i >= 0 && la[i] != Integer.MIN_VALUE ? new double[] {la[i] / 1e6, lo[i] / 1e6} : null;
      }
    }, itd, roads);
    st.osmCameras = camNodes.size();
    st.cameras = cameras.size();
    return new Data(roads, cameras, urban);
  }

  /** Cały przebieg: PBF -> paczki + manifest w katalogu wyjściowym. */
  public static Stats buildPacks(File pbf, File outDir, long osmTimeSec, long buildTimeSec) throws IOException {
    return buildPacks(pbf, null, outDir, osmTimeSec, buildTimeSec);
  }

  public static Stats buildPacks(File pbf, File itdCsv, File outDir, long osmTimeSec, long buildTimeSec) throws IOException {
    return buildPacks(pbf, itdCsv, outDir, osmTimeSec, buildTimeSec, true, false);
  }

  /** urbanInference = true: z rozpoznawaniem terenu zabudowanego (opcja, domyślnie wyłączona, patrz read). */
  public static Stats buildPacks(File pbf, File itdCsv, File outDir, long osmTimeSec, long buildTimeSec, boolean urbanInference) throws IOException {
    return buildPacks(pbf, itdCsv, outDir, osmTimeSec, buildTimeSec, true, urbanInference);
  }

  /** neighbors: uzupełnianie z sąsiednich odcinków (domyślnie włączone), urbanInference: teren zabudowany z budynków (domyślnie wyłączone). */
  public static Stats buildPacks(File pbf, File itdCsv, File outDir, long osmTimeSec, long buildTimeSec, boolean neighbors, boolean urbanInference) throws IOException {
    Stats st = new Stats();
    Data d = read(pbf, itdCsv, st, neighbors, urbanInference);
    writePacks(d.roads, d.cameras, outDir, osmTimeSec, buildTimeSec, st);
    return st;
  }

  /** Zawartość jednego kafelka. */
  static final class TileData {
    final List<Road> roads = new ArrayList<Road>();
    final List<Camera> cams = new ArrayList<Camera>();
  }

  /** Zapis paczek i manifestu z gotowej listy dróg (także do testów). */
  public static void writePacks(List<Road> roads, File outDir, long osmTimeSec, long buildTimeSec, Stats st) throws IOException {
    writePacks(roads, new ArrayList<Camera>(), outDir, osmTimeSec, buildTimeSec, st);
  }

  public static void writePacks(List<Road> roads, List<Camera> cams, File outDir, long osmTimeSec, long buildTimeSec, Stats st) throws IOException {
    if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("nie można utworzyć katalogu " + outDir);
    TreeMap<Long, TileData> tiles = groupByTile(roads, cams);
    // kafelki -> paczki
    TreeMap<Long, TreeMap<Long, TileData>> packs = new TreeMap<Long, TreeMap<Long, TileData>>();
    for (Map.Entry<Long, TileData> e : tiles.entrySet()) {
      int tx = tileXOfKey(e.getKey()), ty = tileYOfKey(e.getKey());
      long pk = PackGrid.key(PackGrid.packOfTile(tx), PackGrid.packOfTile(ty));
      TreeMap<Long, TileData> p = packs.get(pk);
      if (p == null) { p = new TreeMap<Long, TileData>(); packs.put(pk, p); }
      p.put(e.getKey(), e.getValue());
    }
    StringBuilder man = new StringBuilder(packs.size() * 28 + 256);
    man.append("{\"v\":").append(RoadDb.VERSION).append(",\"built\":").append(buildTimeSec).append(",\"osm\":").append(osmTimeSec)
       .append(",\"tileLat\":").append(PackGrid.TILE_LAT_MICRO).append(",\"tileLon\":").append(PackGrid.TILE_LON_MICRO)
       .append(",\"packTiles\":").append(PackGrid.PACK_TILES).append(",\"cams\":").append(cams.size()).append(",\"packs\":[");
    long bytes = 0, entries = 0;
    int camTiles = 0;
    boolean first = true;
    for (Map.Entry<Long, TreeMap<Long, TileData>> e : packs.entrySet()) {
      int px = tileXOfKey(e.getKey()), py = tileYOfKey(e.getKey());
      File f = new File(outDir, PackGrid.name(px, py));
      // czasy w nagłówku paczki zerowe: ta sama zawartość drogowa daje ten sam plik, więc aplikacje pobierają
      // ponownie tylko paczki, w których coś się naprawdę zmieniło (czas budowy jest w manifeście)
      writeFile(f, e.getValue(), 0L, 0L);
      long crc = crcOf(f);
      String sha = sha256Of(f);
      bytes += f.length();
      for (TileData t : e.getValue().values()) { entries += t.roads.size(); if (!t.cams.isEmpty()) camTiles++; }
      if (!first) man.append(',');
      first = false;
      man.append('[').append(px).append(',').append(py).append(',').append(f.length()).append(',').append(crc).append(",\"").append(sha).append("\"]");
    }
    man.append("]}");
    File mf = new File(outDir, "manifest.json");
    File tmp = new File(outDir, "manifest.json.tmp");
    FileOutputStream fo = new FileOutputStream(tmp);
    try { fo.write(man.toString().getBytes(StandardCharsets.UTF_8)); fo.getFD().sync(); } finally { fo.close(); }
    if (mf.exists() && !mf.delete()) throw new IOException("nie można nadpisać manifestu");
    if (!tmp.renameTo(mf)) throw new IOException("nie można zapisać manifestu");
    if (st != null) {
      st.tiles = tiles.size(); st.packs = packs.size(); st.bytes = bytes; st.tileRoadEntries = (int) entries;
      st.cameras = cams.size(); st.cameraTiles = camTiles;
    }
  }

  /** Zapis jednego pliku ze wszystkimi kafelkami (do testów i do narzędzi diagnostycznych). */
  public static void write(List<Road> roads, File out, long osmTimeSec, long buildTimeSec, Stats st) throws IOException {
    write(roads, new ArrayList<Camera>(), out, osmTimeSec, buildTimeSec, st);
  }

  public static void write(List<Road> roads, List<Camera> cams, File out, long osmTimeSec, long buildTimeSec, Stats st) throws IOException {
    TreeMap<Long, TileData> tiles = groupByTile(roads, cams);
    writeFile(out, tiles, osmTimeSec, buildTimeSec);
    if (st != null) {
      long entries = 0;
      for (TileData t : tiles.values()) entries += t.roads.size();
      st.tiles = tiles.size(); st.tileRoadEntries = (int) entries; st.bytes = out.length(); st.packs = 1; st.cameras = cams.size();
    }
  }

  static TreeMap<Long, TileData> groupByTile(List<Road> roads, List<Camera> cams) {
    TreeMap<Long, TileData> tiles = new TreeMap<Long, TileData>();
    for (Road r : roads) {
      for (long key : tilesOf(r)) tile(tiles, key).roads.add(r);
    }
    for (Camera c : cams) tile(tiles, RoadDb.key(PackGrid.tileX(c.lon), PackGrid.tileY(c.lat))).cams.add(c);
    return tiles;
  }

  private static TileData tile(TreeMap<Long, TileData> tiles, long key) {
    TileData t = tiles.get(key);
    if (t == null) { t = new TileData(); tiles.put(key, t); }
    return t;
  }

  static int tileXOfKey(long key) { return (int) (key & 0xFFFFFFFFL); }
  static int tileYOfKey(long key) { return (int) (key >> 32); }

  private static void writeFile(File out, TreeMap<Long, TileData> tiles, long osmTimeSec, long buildTimeSec) throws IOException {
    List<byte[]> blocks = new ArrayList<byte[]>();
    List<long[]> meta = new ArrayList<long[]>();   // tx, ty, crc
    for (Map.Entry<Long, TileData> e : tiles.entrySet()) {
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

  /** Pierwsze 128 bitów SHA-256 pliku (32 znaki szesnastkowe), tak jak zapisuje je spis i liczy aplikacja. */
  public static String sha256Of(File f) throws IOException {
    java.security.MessageDigest d = altergames.carlauncher.limit.core.PackSigning.newSha256();
    FileInputStream in = new FileInputStream(f);
    try {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
    } finally {
      in.close();
    }
    return altergames.carlauncher.limit.core.PackSigning.shortSha256(d.digest());
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

  private static byte[] encodeTile(TileData td, int tx, int ty) {
    List<Road> roads = td.roads;
    ByteArrayOutputStream o = new ByteArrayOutputStream(roads.size() * 60 + td.cams.size() * 16 + 16);
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
    // radary (v2): liczba, potem każdy: id, rodzaj|źródła<<3, kierunek/2 (255 = oba), limit/5, długość odcinka, para, położenie
    varint(o, td.cams.size());
    for (Camera c : td.cams) {
      varint(o, c.id);
      o.write(c.kind | (c.sources << 3));
      o.write(c.dir == Camera.DIR_ANY ? 255 : c.dir / 2);
      o.write(c.limit > 0 ? c.limit / 5 : 0);
      varint(o, c.sectionLen);
      varint(o, c.pair);
      varint(o, zig(Math.round(c.lat * 1e6) - originLat));
      varint(o, zig(Math.round(c.lon * 1e6) - originLon));
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
    File itd = null;
    boolean urban = false, neighbors = true;
    List<String> args = new ArrayList<String>();
    for (String s : a) {
      if (s.startsWith("--itd=")) itd = new File(s.substring(6));
      else if (s.equals("--urban")) urban = true;
      else if (s.equals("--neighbors")) neighbors = true;
      else if (s.equals("--no-neighbors")) neighbors = false;
      else args.add(s);
    }
    if (args.size() < 2) {
      System.err.println("użycie: RdbBuilder [--itd=wykaz.csv] [--no-neighbors] [--urban] wejście.osm.pbf katalog-wyjściowy [minimalna-liczba-paczek]");
      System.exit(2);
    }
    File pbf = new File(args.get(0));
    int minPacks = args.size() > 2 ? Integer.parseInt(args.get(2)) : 1;
    long osm = PbfReader.headerTimestamp(pbf);
    long t0 = System.currentTimeMillis();
    Stats s = buildPacks(pbf, itd, new File(args.get(1)), osm, System.currentTimeMillis() / 1000, neighbors, urban);
    System.out.println(s + " (" + (System.currentTimeMillis() - t0) / 1000 + " s), czas danych OSM: " + osm);
    if (s.packs < minPacks) {
      System.err.println("BŁĄD: za mało paczek (" + s.packs + " < " + minPacks + "), plik wejściowy wygląda na niepełny");
      System.exit(3);
    }
  }
}
