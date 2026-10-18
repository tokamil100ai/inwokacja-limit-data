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
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Czytnik plików .osm.pbf (format OpenStreetMap), tylko to, co potrzebne: drogi (way) i współrzędne punktów (node).
 * Dwa przebiegi po pliku: najpierw drogi, potem punkty tylko tych dróg. Obsługuje bloki surowe i zlib.
 */
public final class PbfReader {
  public interface WaySink {
    /** Droga z tagami (tylko z listy wanted) i listą identyfikatorów punktów. */
    void way(long id, Map<String, String> tags, long[] refs);
  }

  public interface NodeSink {
    void node(long id, double lat, double lon);
  }

  private static final int MAX_HEADER = 64 * 1024;
  private static final int MAX_BLOB = 32 * 1024 * 1024;

  /** Czyta drogi. wanted: klucze tagów do zachowania, filter: czy w ogóle zbierać drogę o tym tagu highway. */
  public static void readWays(File f, java.util.Set<String> wanted, java.util.Set<String> highways, WaySink sink) throws IOException {
    read(f, true, wanted, highways, sink, null);
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
    read(f, false, null, null, null, sink);
  }

  private static void read(File f, boolean ways, java.util.Set<String> wanted, java.util.Set<String> highways,
                           WaySink ws, NodeSink ns) throws IOException {
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
        block(new Pb(raw), ways, wanted, highways, ws, ns);
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

  private static void block(Pb pb, boolean ways, java.util.Set<String> wanted, java.util.Set<String> highways,
                            WaySink ws, NodeSink ns) {
    String[] strings = new String[0];
    long gran = 100, latOff = 0, lonOff = 0;
    int groupsStart = pb.p;
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
    for (Pb g : groups) {
      while (g.more()) {
        int tag = g.tag();
        int field = tag >>> 3, wire = tag & 7;
        if (wire != 2) { g.skip(wire); continue; }
        if (field == 3 && ways) way(g.slice(), strings, wanted, highways, ws);
        else if (field == 2 && !ways) dense(g.slice(), gran, latOff, lonOff, ns);
        else if (field == 1 && !ways) plainNode(g.slice(), gran, latOff, lonOff, ns);
        else g.skip(2);
      }
    }
  }

  private static void way(Pb w, String[] strings, java.util.Set<String> wanted, java.util.Set<String> highways, WaySink sink) {
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
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] < 0 || keys[i] >= strings.length || vals[i] < 0 || vals[i] >= strings.length) return;
      String k = strings[(int) keys[i]];
      if ("highway".equals(k)) hw = strings[(int) vals[i]];
    }
    if (hw == null || !highways.contains(hw)) return;
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

  private static void dense(Pb d, long gran, long latOff, long lonOff, NodeSink sink) {
    long[] ids = null, lats = null, lons = null;
    while (d.more()) {
      int tag = d.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 2) ids = d.packed();
      else if (field == 8 && wire == 2) lats = d.packed();
      else if (field == 9 && wire == 2) lons = d.packed();
      else d.skip(wire);
    }
    if (ids == null || lats == null || lons == null || ids.length != lats.length || ids.length != lons.length) return;
    long id = 0, la = 0, lo = 0;
    for (int i = 0; i < ids.length; i++) {
      id += Pb.unzig(ids[i]); la += Pb.unzig(lats[i]); lo += Pb.unzig(lons[i]);
      sink.node(id, 1e-9 * (latOff + gran * la), 1e-9 * (lonOff + gran * lo));
    }
  }

  private static void plainNode(Pb n, long gran, long latOff, long lonOff, NodeSink sink) {
    long id = 0, la = 0, lo = 0;
    boolean hasLa = false, hasLo = false;
    while (n.more()) {
      int tag = n.tag();
      int field = tag >>> 3, wire = tag & 7;
      if (field == 1 && wire == 0) id = Pb.unzig(n.varint());
      else if (field == 8 && wire == 0) { la = Pb.unzig(n.varint()); hasLa = true; }
      else if (field == 9 && wire == 0) { lo = Pb.unzig(n.varint()); hasLo = true; }
      else n.skip(wire);
    }
    if (hasLa && hasLo) sink.node(id, 1e-9 * (latOff + gran * la), 1e-9 * (lonOff + gran * lo));
  }
}
