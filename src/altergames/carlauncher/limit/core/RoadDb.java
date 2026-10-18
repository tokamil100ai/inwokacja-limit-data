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
  public static final int VERSION = 1;
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
  private final LinkedHashMap<Long, List<Road>> lru = new LinkedHashMap<Long, List<Road>>(32, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long, List<Road>> e) { return size() > CACHE_TILES; }
  };
  private int corrupt;

  private RoadDb(RandomAccessFile raf, int tileLatMicro, int tileLonMicro, long buildTimeSec, long osmTimeSec) {
    this.raf = raf; this.tileLatMicro = tileLatMicro; this.tileLonMicro = tileLonMicro;
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
      if ((b.get() & 0xFF) != VERSION) throw new IOException("nieobsługiwana wersja");
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
      RoadDb db = new RoadDb(raf, tl, tn, bt, ot);
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
      double dLat = radiusM / 110540.0;
      double dLon = radiusM / (Math.cos(Math.toRadians(lat)) * 111320.0);
      int y0 = floorDiv((long) Math.floor((lat - dLat) * 1e6), tileLatMicro), y1 = floorDiv((long) Math.floor((lat + dLat) * 1e6), tileLatMicro);
      int x0 = floorDiv((long) Math.floor((lon - dLon) * 1e6), tileLonMicro), x1 = floorDiv((long) Math.floor((lon + dLon) * 1e6), tileLonMicro);
      if ((y1 - y0 + 1L) * (x1 - x0 + 1L) > 16) return out;   // nierealnie duży obszar: ignorujemy
      Set<Long> seen = new HashSet<Long>();
      for (int ty = y0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++) {
        for (Road r : tile(tx, ty)) if (seen.add(r.id)) out.add(r);
      }
    } catch (RuntimeException e) {
      corrupt++;
    }
    return out;
  }

  /** Ogrzanie pamięci podręcznej kafelkiem w danym miejscu (np. kawałek przed autem). */
  public void prefetch(double lat, double lon) { roadsNear(lat, lon, 30); }

  private List<Road> tile(int tx, int ty) {
    long k = key(tx, ty);
    List<Road> t = lru.get(k);
    if (t != null) return t;
    long[] e = index.get(k);
    if (e == null) return java.util.Collections.emptyList();
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
      t = java.util.Collections.emptyList();
    } catch (RuntimeException ex) {
      corrupt++;
      t = java.util.Collections.emptyList();
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

  private List<Road> decodeTile(byte[] raw, int tx, int ty) {
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
    return roads;
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
