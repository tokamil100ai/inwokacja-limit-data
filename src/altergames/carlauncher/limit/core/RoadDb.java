package altergames.carlauncher.limit.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lokalna baza dróg regionu (plik .rdb), odczyt kafelkami.
 * Format: nagłówek, bloki kafelków (każdy skompresowany deflate, z sumą CRC32), indeks kafelków, znacznik końca.
 * Kafelek to siatka 0,02 x 0,03 stopnia (około 2,2 x 2,0 km). Czytnik ładuje tylko kafelki potrzebne w danym miejscu
 * i trzyma kilka ostatnich w pamięci, więc zużycie pamięci jest małe niezależnie od rozmiaru regionu.
 * Odporny na uszkodzenia: zły kafelek jest pomijany (i liczony), a nie wywraca aplikacji.
 */
public final class RoadDb {
  public static final int MAGIC = 0x4147_4C44;            // "AGLD"
  public static final int END_MAGIC = 0x454E_4421;        // "END!"
  /** Aktualna wersja formatu (zapisywana przez program budujący). v2 dodaje do kafelka radary i odcinkowe pomiary prędkości. */
  public static final int VERSION = 2;
  /** Najstarsza wersja, którą czytamy: paczki v1 (bez radarów) nadal się otwierają. */
  public static final int MIN_VERSION = 1;
  public static final int HEADER_SIZE = 48;
  public static final int INDEX_ENTRY = 24;
  static final int MAX_TILE_BYTES = 8_000_000;
  static final int MAX_TILE_RAW = 40_000_000;
  static final int CACHE_TILES = 4;

  public static final String[] HIGHWAYS = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
      "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service"};

  private final RandomAccessFile raf;
  private final int tileLatMicro, tileLonMicro;
  private final long buildTimeSec, osmTimeSec;
  private final Map<Long, long[]> index = new HashMap<Long, long[]>();   // klucz kafelka -> {offset, długość, crc}
  private final LinkedHashMap<Long, Tile> lru = new LinkedHashMap<Long, Tile>(32, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long, Tile> e) { return size() > CACHE_TILES; }
  };
  private final int version;

  /** Zawartość jednego kafelka: drogi i (od v2) radary. */
  private static final class Tile {
    static final Tile EMPTY = new Tile(java.util.Collections.<Road>emptyList(), java.util.Collections.<Camera>emptyList());
    final List<Road> roads;
    final List<Camera> cams;
    Tile(List<Road> roads, List<Camera> cams) { this.roads = roads; this.cams = cams; }
  }
  private int corrupt;

  private RoadDb(RandomAccessFile raf, int version, int tileLatMicro, int tileLonMicro, long buildTimeSec, long osmTimeSec) {
    this.raf = raf; this.version = version; this.tileLatMicro = tileLatMicro; this.tileLonMicro = tileLonMicro;
    this.buildTimeSec = buildTimeSec; this.osmTimeSec = osmTimeSec;
  }

  public static long key(int tx, int ty) { return ((long) ty << 32) ^ (tx & 0xFFFFFFFFL); }

  /** Otwarcie i sprawdzenie nagłówka oraz indeksu. Zgłasza IOException przy każdej niezgodności. */
  public static RoadDb open(File f) throws IOException {
    RandomAccessFile raf = new RandomAccessFile(f, "r");
    try {
      long len = raf.length();
      if (len < HEADER_SIZE + 8) throw new IOException("plik za krótki");
      byte[] h = new byte[HEADER_SIZE];
      raf.seek(0);
      raf.readFully(h);
      ByteBuffer b = ByteBuffer.wrap(h);
      if (b.getInt() != MAGIC) throw new IOException("zły znacznik pliku");
      int ver = b.get() & 0xFF;
      if (ver < MIN_VERSION || ver > VERSION) throw new IOException("nieobsługiwana wersja");
      b.position(8);
      int tl = b.getInt(), tn = b.getInt();
      long bt = b.getLong(), ot = b.getLong();
      int count = b.getInt();
      long indexOff = b.getLong();
      if (tl <= 0 || tn <= 0 || tl > 1_000_000 || tn > 1_000_000) throw new IOException("zła siatka");
      if (count < 0 || count > 2_000_000) throw new IOException("zła liczba kafelków");
      if (indexOff < HEADER_SIZE || indexOff + (long) count * INDEX_ENTRY + 4 != len) throw new IOException("zły układ pliku");
      raf.seek(len - 4);
      if (raf.readInt() != END_MAGIC) throw new IOException("brak znacznika końca (plik ucięty?)");
      RoadDb db = new RoadDb(raf, ver, tl, tn, bt, ot);
      byte[] idx = new byte[count * INDEX_ENTRY];
      raf.seek(indexOff);
      raf.readFully(idx);
      ByteBuffer ib = ByteBuffer.wrap(idx);
      for (int i = 0; i < count; i++) {
        int tx = ib.getInt(), ty = ib.getInt();
        long off = ib.getLong();
        int blen = ib.getInt(), crc = ib.getInt();
        if (off < HEADER_SIZE || blen <= 0 || blen > MAX_TILE_BYTES || off + blen > indexOff) throw new IOException("zły wpis indeksu " + i);
        db.index.put(key(tx, ty), new long[] {off, blen, crc & 0xFFFFFFFFL});
      }
      return db;
    } catch (IOException e) {
      try { raf.close(); } catch (IOException ignored) { /* nic */ }
      throw e;
    } catch (RuntimeException e) {
      try { raf.close(); } catch (IOException ignored) { /* nic */ }
      throw new IOException("uszkodzony plik: " + e);
    }
  }

  public int version() { return version; }
  public long buildTimeSec() { return buildTimeSec; }
  public long osmTimeSec() { return osmTimeSec; }
  public synchronized int tileCount() { return index.size(); }
  public synchronized int corruptTiles() { return corrupt; }

  public synchronized void close() {
    try { raf.close(); } catch (IOException ignored) { /* nic */ }
    lru.clear();
  }

  /** Drogi z kafelków obejmujących kwadrat o boku 2*radiusM wokół punktu (z duplikatami usuniętymi). Nigdy nie zgłasza wyjątku. */
  public synchronized List<Road> roadsNear(double lat, double lon, int radiusM) {
    List<Road> out = new ArrayList<Road>();
    try {
      int[] r = tileRange(lat, lon, radiusM, 16);
      if (r == null) return out;                        // nierealnie duży obszar: ignorujemy
      Set<Long> seen = new HashSet<Long>();
      for (int ty = r[1]; ty <= r[3]; ty++) for (int tx = r[0]; tx <= r[2]; tx++) {
        for (Road rd : tile(tx, ty).roads) if (seen.add(rd.id)) out.add(rd);
      }
    } catch (RuntimeException e) {
      corrupt++;
    }
    return out;
  }

  /** Radary (i końce odcinków) w promieniu radiusM metrów od punktu. Puste dla paczek v1. Nigdy nie zgłasza wyjątku. */
  public synchronized List<Camera> camerasNear(double lat, double lon, int radiusM) {
    List<Camera> out = new ArrayList<Camera>();
    try {
      int[] r = tileRange(lat, lon, radiusM, 64);       // do ok. 7 km: radary są rzadkie, ale kafelki trzeba rozpakować
      if (r == null) return out;
      double cosLat = Math.cos(Math.toRadians(lat));
      // radar leży w jednym kafelku (nie jest powielany jak droga), więc bez usuwania powtórzeń: to samo urządzenie
      // może mieć kilka wpisów (np. kamera na czerwonym świetle dla kilku kierunków dojazdu)
      for (int ty = r[1]; ty <= r[3]; ty++) for (int tx = r[0]; tx <= r[2]; tx++) {
        for (Camera c : tile(tx, ty).cams) {
          double dy = (c.lat - lat) * 110540.0, dx = (c.lon - lon) * 111320.0 * cosLat;
          if (dx * dx + dy * dy <= (double) radiusM * radiusM) out.add(c);
        }
      }
    } catch (RuntimeException e) {
      corrupt++;
    }
    return out;
  }

  /** Zakres kafelków {x0, y0, x1, y1} obejmujący kwadrat wokół punktu albo null, gdy kafelków byłoby więcej niż maxTiles. */
  private int[] tileRange(double lat, double lon, int radiusM, int maxTiles) {
    double dLat = radiusM / 110540.0;
    double dLon = radiusM / (Math.cos(Math.toRadians(lat)) * 111320.0);
    int y0 = floorDiv((long) Math.floor((lat - dLat) * 1e6), tileLatMicro), y1 = floorDiv((long) Math.floor((lat + dLat) * 1e6), tileLatMicro);
    int x0 = floorDiv((long) Math.floor((lon - dLon) * 1e6), tileLonMicro), x1 = floorDiv((long) Math.floor((lon + dLon) * 1e6), tileLonMicro);
    if ((y1 - y0 + 1L) * (x1 - x0 + 1L) > maxTiles) return null;
    return new int[] {x0, y0, x1, y1};
  }

  /** Ogrzanie pamięci podręcznej kafelkiem w danym miejscu (np. kawałek przed autem). */
  public void prefetch(double lat, double lon) { roadsNear(lat, lon, 30); }

  private Tile tile(int tx, int ty) {
    long k = key(tx, ty);
    Tile t = lru.get(k);
    if (t != null) return t;
    long[] e = index.get(k);
    if (e == null) return Tile.EMPTY;
    try {
      byte[] comp = new byte[(int) e[1]];
      raf.seek(e[0]);
      raf.readFully(comp);
      CRC32 crc = new CRC32();
      crc.update(comp, 0, comp.length);
      if (crc.getValue() != e[2]) throw new IOException("suma kontrolna kafelka");
      t = decodeTile(inflate(comp), tx, ty);
    } catch (IOException ex) {
      corrupt++;
      t = Tile.EMPTY;
    } catch (RuntimeException ex) {
      corrupt++;
      t = Tile.EMPTY;
    }
    lru.put(k, t);
    return t;
  }

  private static byte[] inflate(byte[] comp) throws IOException {
    Inflater inf = new Inflater();
    try {
      inf.setInput(comp);
      ByteArrayOutputStream out = new ByteArrayOutputStream(comp.length * 3);
      byte[] buf = new byte[64 * 1024];
      while (!inf.finished()) {
        int n = inf.inflate(buf);
        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IOException("ucięty kafelek");
        out.write(buf, 0, n);
        if (out.size() > MAX_TILE_RAW) throw new IOException("kafelek za duży po rozpakowaniu");
      }
      return out.toByteArray();
    } catch (DataFormatException e) {
      throw new IOException("zły strumień deflate");
    } finally {
      inf.end();
    }
  }

  private Tile decodeTile(byte[] raw, int tx, int ty) {
    ByteBuffer b = ByteBuffer.wrap(raw);
    long originLat = (long) ty * tileLatMicro, originLon = (long) tx * tileLonMicro;
    int n = (int) varint(b);
    if (n < 0 || n > 200_000) throw new IllegalStateException("zła liczba dróg");
    List<Road> roads = new ArrayList<Road>(n);
    for (int i = 0; i < n; i++) {
      long id = varint(b);
      int flags = b.get() & 0xFF;
      int hw = flags & 15, ow = (flags >> 4) & 3, src = (flags >> 6) & 3;
      int lim = (b.get() & 0xFF), fw = (b.get() & 0xFF), bw = (b.get() & 0xFF);
      int pts = (int) varint(b);
      if (hw >= HIGHWAYS.length || ow > 2 || src > 2 || pts < 2 || pts > OverpassParser.MAX_POINTS) throw new IllegalStateException("zła droga");
      double[] la = new double[pts], lo = new double[pts];
      long cl = originLat + unzig(varint(b)), co = originLon + unzig(varint(b));
      la[0] = cl / 1e6; lo[0] = co / 1e6;
      for (int k = 1; k < pts; k++) {
        cl += unzig(varint(b)); co += unzig(varint(b));
        la[k] = cl / 1e6; lo[k] = co / 1e6;
      }
      int oneway = ow == 1 ? 1 : ow == 2 ? -1 : 0;
      roads.add(new Road(id, HIGHWAYS[hw], oneway, lim5(lim), Road.Source.values()[src], lim5(fw), lim5(bw), la, lo));
    }
    List<Camera> cams = java.util.Collections.emptyList();
    if (version >= 2 && b.hasRemaining()) cams = decodeCameras(b, originLat, originLon);
    return new Tile(roads, cams);
  }

  /** Radary kafelka (v2): liczba, potem każdy: id, bajt rodzaj|źródła, kierunek/2, limit/5, długość odcinka, para, położenie. */
  private static List<Camera> decodeCameras(ByteBuffer b, long originLat, long originLon) {
    int n = (int) varint(b);
    if (n < 0 || n > 20_000) throw new IllegalStateException("zła liczba radarów");
    List<Camera> out = new ArrayList<Camera>(n);
    for (int i = 0; i < n; i++) {
      long id = varint(b);
      int kb = b.get() & 0xFF;
      int kind = kb & 7, src = (kb >> 3) & 3;
      int dir = b.get() & 0xFF, lim = b.get() & 0xFF;
      long len = varint(b), pair = varint(b);
      long la = originLat + unzig(varint(b)), lo = originLon + unzig(varint(b));
      if (kind > Camera.RAIL_LIGHT || src == 0 || (dir > 179 && dir != 255) || len > 100_000) throw new IllegalStateException("zły radar");
      out.add(new Camera(id, kind, la / 1e6, lo / 1e6, dir == 255 ? Camera.DIR_ANY : dir * 2, lim5(lim) == Maxspeed.UNKNOWN ? 0 : lim5(lim),
          (int) len, src, pair));
    }
    return out;
  }

  private static int lim5(int v) { return v == 0 ? Maxspeed.UNKNOWN : (Maxspeed.validLimit(v * 5) ? v * 5 : Maxspeed.UNKNOWN); }

  static long varint(ByteBuffer b) {
    long r = 0;
    int s = 0;
    while (true) {
      int c = b.get() & 0xFF;
      r |= (long) (c & 0x7F) << s;
      if ((c & 0x80) == 0) return r;
      s += 7;
      if (s > 63) throw new IllegalStateException("zbyt długi varint");
    }
  }

  static long unzig(long v) { return (v >>> 1) ^ -(v & 1); }

  private static int floorDiv(long a, int d) { return (int) Math.floorDiv(a, (long) d); }

  /** Drogi bazy do testów porównawczych. */
  public synchronized Iterator<Long> tileKeys() { return new ArrayList<Long>(index.keySet()).iterator(); }
}
