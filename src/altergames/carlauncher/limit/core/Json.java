package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimalny parser i zapis JSON, bez zależności (ten sam kod działa na Androidzie i w testach na komputerze).
 * Ograniczenia chronią przed złośliwym lub uszkodzonym wejściem: głębokość, rozmiar, długość napisu.
 * Typy wyniku: Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Double, Boolean, null.
 */
public final class Json {
  public static final class JsonException extends RuntimeException {
    public JsonException(String m) { super(m); }
  }

  private static final int MAX_DEPTH = 40;
  private static final int MAX_CHARS = 8_000_000;

  private final String s;
  private int i;

  private Json(String s) { this.s = s; }

  public static Object parse(String text) {
    if (text == null) throw new JsonException("brak tekstu");
    if (text.length() > MAX_CHARS) throw new JsonException("za duży JSON");
    Json p = new Json(text);
    p.ws();
    Object v = p.value(0);
    p.ws();
    if (p.i != p.s.length()) throw new JsonException("śmieci po JSON na pozycji " + p.i);
    return v;
  }

  private void ws() {
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') i++;
      else break;
    }
  }

  private Object value(int depth) {
    if (depth > MAX_DEPTH) throw new JsonException("za głębokie zagnieżdżenie");
    if (i >= s.length()) throw new JsonException("nagły koniec");
    char c = s.charAt(i);
    switch (c) {
      case '{': return object(depth);
      case '[': return array(depth);
      case '"': return string();
      case 't': return literal("true", Boolean.TRUE);
      case 'f': return literal("false", Boolean.FALSE);
      case 'n': return literal("null", null);
      default: return number();
    }
  }

  private Object literal(String word, Object val) {
    if (!s.startsWith(word, i)) throw new JsonException("zły literał na pozycji " + i);
    i += word.length();
    return val;
  }

  private Map<String, Object> object(int depth) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    i++; // {
    ws();
    if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
    while (true) {
      ws();
      if (i >= s.length() || s.charAt(i) != '"') throw new JsonException("oczekiwano klucza na pozycji " + i);
      String k = string();
      ws();
      if (i >= s.length() || s.charAt(i) != ':') throw new JsonException("oczekiwano ':' na pozycji " + i);
      i++;
      ws();
      m.put(k, value(depth + 1));
      ws();
      if (i >= s.length()) throw new JsonException("nagły koniec obiektu");
      char c = s.charAt(i++);
      if (c == ',') continue;
      if (c == '}') return m;
      throw new JsonException("zły znak w obiekcie na pozycji " + (i - 1));
    }
  }

  private List<Object> array(int depth) {
    List<Object> a = new ArrayList<Object>();
    i++; // [
    ws();
    if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
    while (true) {
      ws();
      a.add(value(depth + 1));
      ws();
      if (i >= s.length()) throw new JsonException("nagły koniec tablicy");
      char c = s.charAt(i++);
      if (c == ',') continue;
      if (c == ']') return a;
      throw new JsonException("zły znak w tablicy na pozycji " + (i - 1));
    }
  }

  private String string() {
    StringBuilder b = new StringBuilder();
    i++; // "
    while (true) {
      if (i >= s.length()) throw new JsonException("nagły koniec napisu");
      char c = s.charAt(i++);
      if (c == '"') return b.toString();
      if (c == '\\') {
        if (i >= s.length()) throw new JsonException("nagły koniec escape");
        char e = s.charAt(i++);
        switch (e) {
          case '"': b.append('"'); break;
          case '\\': b.append('\\'); break;
          case '/': b.append('/'); break;
          case 'b': b.append('\b'); break;
          case 'f': b.append('\f'); break;
          case 'n': b.append('\n'); break;
          case 'r': b.append('\r'); break;
          case 't': b.append('\t'); break;
          case 'u': {
            if (i + 4 > s.length()) throw new JsonException("zły \\u");
            int cp = 0;
            for (int k = 0; k < 4; k++) {
              int d = Character.digit(s.charAt(i + k), 16);
              if (d < 0) throw new JsonException("zła cyfra w \\u");
              cp = cp * 16 + d;
            }
            i += 4;
            b.append((char) cp);
            break;
          }
          default: throw new JsonException("zły escape \\" + e);
        }
      } else {
        if (c < 0x20) throw new JsonException("znak sterujący w napisie");
        b.append(c);
      }
      if (b.length() > 1_000_000) throw new JsonException("za długi napis");
    }
  }

  private Double number() {
    int st = i;
    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
    while (i < s.length()) {
      char c = s.charAt(i);
      if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') i++;
      else break;
    }
    if (st == i) throw new JsonException("oczekiwano wartości na pozycji " + st);
    try {
      double d = Double.parseDouble(s.substring(st, i));
      if (Double.isNaN(d) || Double.isInfinite(d)) throw new JsonException("zła liczba");
      return d;
    } catch (NumberFormatException e) {
      throw new JsonException("zła liczba na pozycji " + st);
    }
  }

  /* ---------- wygodne, bezpieczne odczyty ---------- */

  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

  @SuppressWarnings("unchecked")
  public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

  public static String str(Object o) { return o instanceof String ? (String) o : null; }

  public static double num(Object o, double def) { return o instanceof Double ? (Double) o : def; }

  /* ---------- zapis ---------- */

  public static String write(Object o) {
    StringBuilder b = new StringBuilder();
    write(b, o, 0);
    return b.toString();
  }

  private static void write(StringBuilder b, Object o, int depth) {
    if (depth > MAX_DEPTH) throw new JsonException("za głębokie");
    if (o == null) { b.append("null"); return; }
    if (o instanceof String) { quote(b, (String) o); return; }
    if (o instanceof Boolean) { b.append(o.toString()); return; }
    if (o instanceof Number) {
      Number n = (Number) o;
      double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) { b.append("null"); return; }
      if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
      return;
    }
    if (o instanceof Map) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
        if (!first) b.append(',');
        first = false;
        quote(b, String.valueOf(e.getKey()));
        b.append(':');
        write(b, e.getValue(), depth + 1);
      }
      b.append('}');
      return;
    }
    if (o instanceof List) {
      b.append('[');
      boolean first = true;
      for (Object x : (List<?>) o) {
        if (!first) b.append(',');
        first = false;
        write(b, x, depth + 1);
      }
      b.append(']');
      return;
    }
    throw new JsonException("nieobsługiwany typ " + o.getClass().getName());
  }

  private static void quote(StringBuilder b, String s) {
    b.append('"');
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
      }
    }
    b.append('"');
  }
}
