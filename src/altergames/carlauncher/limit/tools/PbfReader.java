package altergames.carlauncher.limit.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Czytnik plików .osm.pbf (format OpenStreetMap), tylko to, co potrzebne: drogi (way), relacje wybranego typu
 * i współrzędne punktów (node), także tych z tagami (np. fotoradary).
 * Dwa przebiegi po pliku: najpierw drogi i relacje, potem punkty (tylko potrzebne drogom i relacjom oraz punkty z tagami).
 * Obsługuje bloki surowe i zlib.
 */
public final class PbfReader {
  public interface WaySink {
    /** Droga z tagami (tylko z listy wanted) i listą identyfikatorów punktów. */
    void way(long id, Map<String, String> tags, long[] refs);
  }

  /** Budynek (droga z tagiem building): przekazujemy tylko identyfikator jej pierwszego punktu, który wystarcza do policzenia gęstości zabudowy. */
  public interface BuildingSink {
    void building(long firstNodeRef);
  }

  public interface NodeSink {
    void node(long id, double lat, double lon);
  }

  /** Punkt, który ma któryś z szukanych tagów (np. highway=speed_camera). */
  public interface TaggedNodeSink {
    void node(long id, double lat, double lon, Map<String, String> tags);
  }

  public static final int MEMBER_NODE = 0, MEMBER_WAY = 1, MEMBER_RELATION = 2;

  public interface RelationSink {
    /** Relacja z wybranymi tagami oraz członkami: identyfikator, typ (MEMBER_*) i rola każdego. */
    void relation(long id, Map<String, String> tags, long[] memberIds, int[] memberTypes, String[] roles);
  }

  /**
   * Które punkty z tagami nas obchodzą: wzorce "klucz" (dowolna wartość) albo "klucz=wartość". Dzięki temu nie zbieramy
   * milionów punktów typu highway=crossing, tylko np. highway=speed_camera.
   */
  public static final class TagFilter {
    private final Set<String> keys = new java.util.HashSet<String>(), pairKeys = new java.util.HashSet<String>(), pairs = new java.util.HashSet<String>();

    public TagFilter(String... patterns) {
      for (String p : patterns) {
        int eq = p.indexOf('=');
        if (eq < 0) keys.add(p);
        else { pairKeys.add(p.substring(0, eq)); pairs.add(p); }
      }
    }

    boolean matches(String k, String v) {
      return keys.contains(k) || (pairKeys.contains(k) && pairs.contains(k + "=" + v));
    }
  }

  private static final int MAX_HEADER = 64 * 1024;
  private static final int MAX_BLOB = 32 * 1024 * 1024;

  /** Co i dokąd przekazywać z jednego przebiegu po pliku. Pola nieużywane zostają null. */
  private static final class Spec {
    Set<String> wayKeys, highways;                 // drogi: tagi do zachowania, dozwolone typy highway
    WaySink ws;
    NodeSink ns;                                    // wszystkie punkty (współrzędne)
    TagFilter nodeFilter;                           // punkty pasujące do filtra trafiają też do ts
    TaggedNodeSink ts;
    Set<String> relationTypes, relationKeys;        // relacje o tagu type z tego zbioru, z zachowaniem tych kluczy
    RelationSink rs;
    BuildingSink bs;                                // budynki (opcjonalnie)
  }

  /** Czyta drogi. wanted: klucze tagów do zachowania, filter: czy w ogóle zbierać drogę o tym tagu highway. */
  public static void readWays(File f, Set<String> wanted, Set<String> highways, WaySink sink) throws IOException {
    readWaysAndRelations(f, wanted, highways, sink, null, null, null);
  }

  /**
   * Przebieg pierwszy: drogi oraz relacje, których tag type jest w relationTypes (z zachowaniem kluczy relationKeys).
   * Relacje w pliku idą po drogach, więc wystarczy jedno czytanie.
   */
  public static void readWaysAndRelations(File f, Set<String> wanted, Set<String> highways, WaySink ws,
                                          Set<String> relationTypes, Set<String> relationKeys, RelationSink rs) throws IOException {
    Spec s = new Spec();
    s.wayKeys = wanted; s.highways = highways; s.ws = ws;
    s.relationTypes = relationTypes; s.relationKeys = relationKeys; s.rs = rs;
    read(f, s);
  }

  /**
   * Przebieg pierwszy z budynkami: jak readWaysAndRelations, a do tego pierwszy punkt każdej drogi z tagiem building
   * (ale bez highway), żeby z gęstości zabudowy rozpoznać teren zabudowany. bs może być null.
   */
  public static void readWaysRelationsAndBuildings(File f, Set<String> wanted, Set<String> highways, WaySink ws,
                                                   Set<String> relationTypes, Set<String> relationKeys, RelationSink rs,
                                                   BuildingSink bs) throws IOException {
    Spec s = new Spec();
    s.wayKeys = wanted; s.highways = highways; s.ws = ws;
    s.relationTypes = relationTypes; s.relationKeys = relationKeys; s.rs = rs; s.bs = bs;
    read(f, s);
  }

  /** Czas danych (osmosis_replication_timestamp z nagłówka pliku, w sekundach) albo 0, gdy go nie ma. */
  public static long headerTimestamp(File f) throws IOException {
    InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
    try {
      DataInputStream d = new DataInputStream(in);
      int hlen = d.readInt();
      if (hlen <= 0 || hlen > MAX_HEADER) throw new IOException("zły nagłówek bloku: " + hlen);
      byte[] hb = new byte[hlen];
      d.readFully(hb);
      String type = null;
      int datasize = -1;
      Pb h = new Pb(hb);
      while (h.more()) {
        int tag = h.tag();
        int field = tag >>> 3, wire = tag & 7;
        if (field == 1 && wire == 2) type = h.string();
        else if (field == 3 && wire == 0) datasize = (int) h.varint();
        else h.skip(wire);
      }
      if (!"OSMHeader".equals(type) || datasize < 0 || datasize > MAX_BLOB) return 0;
      byte[] blob = new byte[datasize];
      d.readFully(blob);
      Pb hdr = new Pb(unpack(blob));
      long ts = 0;
      while (hdr.more()) {
        int tag = hdr.tag();
        int field = tag >>> 3, wire = tag & 7;
        if (field == 32 && wire == 0) ts = hdr.varint(); else hdr.skip(wire);
      }
      return ts;
    } catch (EOFException e) {
      return 0;
    } finally {
      try { in.close(); } catch (IOException ignored) { /* nic */ }
    }
  }

  public static void readNodes(File f, NodeSink sink) throws IOException {
    readNodes(f, sink, null, null);
  }

  /** Przebieg drugi: współrzędne wszystkich punktów (sink) oraz punkty pasujące do filtra razem z ich tagami (tagged). */
  public static void readNodes(File f, NodeSink sink, TagFilter filter, TaggedNodeSink tagged) throws IOException {
    Spec s = new Spec();
    s.ns = sink; s.nodeFilter = filter; s.ts = tagged;
    read(f, s);
  }

  private static void read(File f, Spec spec) throws IOException {
    InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 20);
    try {
      DataInputStream d = new DataInputStream(in);
      while (true) {
        int hlen;
        try { hlen = d.readInt(); } catch (EOFException e) { break; }
        if (hlen <= 0 || hlen > MAX_HEADER) throw new IOException("zły nagłówek bloku: " + hlen);
        byte[] hb = new byte[hlen];
        d.readFully(hb);
        String type = null;
        int datasize = -1;
        Pb h = new Pb(hb);
        while (h.more()) {
          int tag = h.tag();
          int field = tag >>> 3, wire = tag & 7;
          if (field == 1 && wire == 2) type = h.string();
          else if (field == 3 && wire == 0) datasize = (int) h.varint();
          else h.skip(wire);
        }
        if (datasize < 0 || datasize > MAX_BLOB) throw new IOException("zły rozmiar bloku: " + datasize);
        byte[] blob = new byte[datasize];
        d.readFully(blob);
        if (!"OSMData".equals(type)) continue;
        byte[] raw = unpack(blob);
        block(new Pb(raw), spec);
      }
    } finally {
      try { in.close(); } catch (IOException ignored) { /* nic */ }
    }
  }

  private static byte[] unpack(byte[] blob) throws IOException {
    Pb b = new Pb(blob);
    byte[] rawData = null, zlib = null;
    int rawSize = -1;
    while (b.more()) {
      int tag = b.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 2) rawData = b.bytes();
      else if (field == 2 && wire == 0) rawSize = (int) b.varint();
      else if (field == 3 && wire == 2) zlib = b.bytes();
      else if (field == 4 && wire == 2) throw new IOException("kompresja lzma nie jest obsługiwana");
      else b.skip(wire);
    }
    if (rawData != null) return rawData;
    if (zlib == null) throw new IOException("pusty blok");
    Inflater inf = new Inflater();
    try {
      inf.setInput(zlib);
      ByteArrayOutputStream out = new ByteArrayOutputStream(rawSize > 0 ? rawSize : zlib.length * 4);
      byte[] buf = new byte[64 * 1024];
      while (!inf.finished()) {
        int n = inf.inflate(buf);
        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IOException("ucięty blok zlib");
        out.write(buf, 0, n);
        if (out.size() > MAX_BLOB * 4) throw new IOException("blok za duży po rozpakowaniu");
      }
      return out.toByteArray();
    } catch (DataFormatException e) {
      throw new IOException("zły strumień zlib");
    } finally {
      inf.end();
    }
  }

  private static void block(Pb pb, Spec sp) {
    String[] strings = new String[0];
    long gran = 100, latOff = 0, lonOff = 0;
    // pierwszy przebieg po polach: tablica napisów i parametry
    java.util.List<Pb> groups = new java.util.ArrayList<Pb>();
    while (pb.more()) {
      int tag = pb.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 2) {
        Pb st = pb.slice();
        java.util.List<String> l = new java.util.ArrayList<String>();
        while (st.more()) {
          int t = st.tag();
          if ((t >>> 3) == 1 && (t & 7) == 2) l.add(st.string()); else st.skip(t & 7);
        }
        strings = l.toArray(new String[0]);
      } else if (field == 2 && wire == 2) groups.add(pb.slice());
      else if (field == 17 && wire == 0) gran = pb.varint();
      else if (field == 19 && wire == 0) latOff = pb.varint();
      else if (field == 20 && wire == 0) lonOff = pb.varint();
      else pb.skip(wire);
    }
    boolean wantNodes = sp.ns != null || sp.ts != null;
    for (Pb g : groups) {
      while (g.more()) {
        int tag = g.tag();
        int field = tag >>> 3, wire = tag & 7;
        if (wire != 2) { g.skip(wire); continue; }
        if (field == 3 && (sp.ws != null || sp.bs != null)) way(g.slice(), strings, sp.wayKeys, sp.highways, sp.ws, sp.bs);
        else if (field == 2 && wantNodes) dense(g.slice(), strings, gran, latOff, lonOff, sp);
        else if (field == 1 && wantNodes) plainNode(g.slice(), strings, gran, latOff, lonOff, sp);
        else if (field == 4 && sp.rs != null) relation(g.slice(), strings, sp);
        else g.skip(2);
      }
    }
  }

  private static void way(Pb w, String[] strings, Set<String> wanted, Set<String> highways, WaySink sink, BuildingSink buildings) {
    long id = 0;
    long[] keys = null, vals = null, refs = null;
    while (w.more()) {
      int tag = w.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 0) id = w.varint();
      else if (field == 2 && wire == 2) keys = w.packed();
      else if (field == 3 && wire == 2) vals = w.packed();
      else if (field == 8 && wire == 2) refs = w.packed();
      else w.skip(wire);
    }
    if (keys == null || vals == null || refs == null || keys.length != vals.length || refs.length < 2) return;
    Map<String, String> tags = null;
    String hw = null;
    boolean building = false;
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] < 0 || keys[i] >= strings.length || vals[i] < 0 || vals[i] >= strings.length) return;
      String k = strings[(int) keys[i]];
      if ("highway".equals(k)) hw = strings[(int) vals[i]];
      else if ("building".equals(k) && !"no".equals(strings[(int) vals[i]])) building = true;
    }
    if (hw == null) {
      if (building && buildings != null) buildings.building(Pb.unzig(refs[0]));
      return;
    }
    if (sink == null || !highways.contains(hw)) return;
    tags = new HashMap<String, String>();
    for (int i = 0; i < keys.length; i++) {
      String k = strings[(int) keys[i]];
      if (wanted.contains(k)) tags.put(k, strings[(int) vals[i]]);
    }
    long[] abs = new long[refs.length];
    long cur = 0;
    for (int i = 0; i < refs.length; i++) { cur += Pb.unzig(refs[i]); abs[i] = cur; }
    sink.way(id, tags, abs);
  }

  private static void relation(Pb r, String[] strings, Spec sp) {
    long id = 0;
    long[] keys = null, vals = null, roles = null, mem = null, types = null;
    while (r.more()) {
      int tag = r.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 0) id = r.varint();
      else if (field == 2 && wire == 2) keys = r.packed();
      else if (field == 3 && wire == 2) vals = r.packed();
      else if (field == 8 && wire == 2) roles = r.packed();
      else if (field == 9 && wire == 2) mem = r.packed();
      else if (field == 10 && wire == 2) types = r.packed();
      else r.skip(wire);
    }
    if (keys == null || vals == null || keys.length != vals.length) return;
    String type = null;
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] < 0 || keys[i] >= strings.length || vals[i] < 0 || vals[i] >= strings.length) return;
      if ("type".equals(strings[(int) keys[i]])) type = strings[(int) vals[i]];
    }
    if (type == null || sp.relationTypes == null || !sp.relationTypes.contains(type)) return;
    if (roles == null || mem == null || types == null || roles.length != mem.length || mem.length != types.length) return;
    Map<String, String> tags = new HashMap<String, String>();
    for (int i = 0; i < keys.length; i++) {
      String k = strings[(int) keys[i]];
      if (sp.relationKeys == null || sp.relationKeys.contains(k)) tags.put(k, strings[(int) vals[i]]);
    }
    long[] ids = new long[mem.length];
    int[] ty = new int[mem.length];
    String[] ro = new String[mem.length];
    long cur = 0;
    for (int i = 0; i < mem.length; i++) {
      cur += Pb.unzig(mem[i]);
      ids[i] = cur;
      ty[i] = (int) types[i];
      ro[i] = roles[i] >= 0 && roles[i] < strings.length ? strings[(int) roles[i]] : "";
    }
    sp.rs.relation(id, tags, ids, ty, ro);
  }

  private static void dense(Pb d, String[] strings, long gran, long latOff, long lonOff, Spec sp) {
    long[] ids = null, lats = null, lons = null, kv = null;
    while (d.more()) {
      int tag = d.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 2) ids = d.packed();
      else if (field == 8 && wire == 2) lats = d.packed();
      else if (field == 9 && wire == 2) lons = d.packed();
      else if (field == 10 && wire == 2) kv = (sp.ts != null && sp.nodeFilter != null) ? d.packed() : skipPacked(d);
      else d.skip(wire);
    }
    if (ids == null || lats == null || lons == null || ids.length != lats.length || ids.length != lons.length) return;
    long id = 0, la = 0, lo = 0;
    int kp = 0;
    for (int i = 0; i < ids.length; i++) {
      id += Pb.unzig(ids[i]); la += Pb.unzig(lats[i]); lo += Pb.unzig(lons[i]);
      double lat = 1e-9 * (latOff + gran * la), lon = 1e-9 * (lonOff + gran * lo);
      if (sp.ns != null) sp.ns.node(id, lat, lon);
      if (kv != null) {
        // tagi tego punktu: pary (klucz, wartość) zakończone zerem
        Map<String, String> tags = null;
        boolean wanted = false;
        while (kp < kv.length && kv[kp] != 0) {
          if (kp + 1 >= kv.length) { kp = kv.length; break; }
          long k = kv[kp], v = kv[kp + 1];
          kp += 2;
          if (k < 0 || k >= strings.length || v < 0 || v >= strings.length) continue;
          if (tags == null) tags = new HashMap<String, String>();
          String ks = strings[(int) k];
          String vs = strings[(int) v];
          tags.put(ks, vs);
          if (sp.nodeFilter.matches(ks, vs)) wanted = true;
        }
        kp++;                                                       // zero kończące
        if (wanted) sp.ts.node(id, lat, lon, tags);
      }
    }
  }

  private static long[] skipPacked(Pb d) {
    d.skip(2);
    return null;
  }

  private static void plainNode(Pb n, String[] strings, long gran, long latOff, long lonOff, Spec sp) {
    long id = 0, la = 0, lo = 0;
    boolean hasLa = false, hasLo = false;
    long[] keys = null, vals = null;
    while (n.more()) {
      int tag = n.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 0) id = Pb.unzig(n.varint());
      else if (field == 2 && wire == 2) keys = (sp.ts != null && sp.nodeFilter != null) ? n.packed() : skipPacked(n);
      else if (field == 3 && wire == 2) vals = (sp.ts != null && sp.nodeFilter != null) ? n.packed() : skipPacked(n);
      else if (field == 8 && wire == 0) { la = Pb.unzig(n.varint()); hasLa = true; }
      else if (field == 9 && wire == 0) { lo = Pb.unzig(n.varint()); hasLo = true; }
      else n.skip(wire);
    }
    if (!hasLa || !hasLo) return;
    double lat = 1e-9 * (latOff + gran * la), lon = 1e-9 * (lonOff + gran * lo);
    if (sp.ns != null) sp.ns.node(id, lat, lon);
    if (keys != null && vals != null && keys.length == vals.length && sp.ts != null) {
      Map<String, String> tags = new HashMap<String, String>();
      boolean wanted = false;
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] < 0 || keys[i] >= strings.length || vals[i] < 0 || vals[i] >= strings.length) continue;
        String ks = strings[(int) keys[i]];
        String vs = strings[(int) vals[i]];
        tags.put(ks, vs);
        if (sp.nodeFilter.matches(ks, vs)) wanted = true;
      }
      if (wanted) sp.ts.node(id, lat, lon, tags);
    }
  }
}
