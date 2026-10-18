package altergames.carlauncher.limit.tools;

/** Minimalny czytnik formatu protobuf (wire format), wystarczający do plików .osm.pbf. Bez zewnętrznych bibliotek. */
final class Pb {
  final byte[] b;
  int p;
  final int end;

  Pb(byte[] b) { this(b, 0, b.length); }

  Pb(byte[] b, int off, int end) {
    if (off < 0 || end > b.length || off > end) throw new IllegalArgumentException("zły zakres");
    this.b = b; this.p = off; this.end = end;
  }

  boolean more() { return p < end; }

  long varint() {
    long r = 0;
    int s = 0;
    while (true) {
      if (p >= end) throw new IllegalStateException("nagły koniec danych");
      int c = b[p++] & 0xFF;
      r |= (long) (c & 0x7F) << s;
      if ((c & 0x80) == 0) return r;
      s += 7;
      if (s > 63) throw new IllegalStateException("zbyt długi varint");
    }
  }

  /** Numer pola w górnych bitach, typ zapisu w trzech dolnych. */
  int tag() { return (int) varint(); }

  /** Podzakres bajtów dla pola typu "length-delimited". */
  Pb slice() {
    long len = varint();
    if (len < 0 || p + len > end) throw new IllegalStateException("zła długość pola");
    Pb s = new Pb(b, p, p + (int) len);
    p += (int) len;
    return s;
  }

  byte[] bytes() {
    Pb s = slice();
    byte[] out = new byte[s.end - s.p];
    System.arraycopy(b, s.p, out, 0, out.length);
    return out;
  }

  /** Odczyt pola tekstowego (UTF-8). */
  String string() {
    Pb s = slice();
    return new String(s.b, s.p, s.end - s.p, java.nio.charset.StandardCharsets.UTF_8);
  }

  void skip(int wire) {
    switch (wire) {
      case 0: varint(); break;
      case 1: advance(8); break;
      case 2: { long len = varint(); if (len < 0 || p + len > end) throw new IllegalStateException("zła długość"); p += (int) len; break; }
      case 5: advance(4); break;
      default: throw new IllegalStateException("nieobsługiwany typ zapisu " + wire);
    }
  }

  private void advance(int n) {
    if (p + n > end) throw new IllegalStateException("nagły koniec danych");
    p += n;
  }

  static long unzig(long v) { return (v >>> 1) ^ -(v & 1); }

  /** Pole spakowane: ciąg varintów. */
  long[] packed() {
    Pb s = slice();
    long[] out = new long[16];
    int n = 0;
    while (s.more()) {
      if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
      out[n++] = s.varint();
    }
    return java.util.Arrays.copyOf(out, n);
  }
}
