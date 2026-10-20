package altergames.carlauncher.limit.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Klient TomTom Snap to Roads: dla kilku ostatnich pozycji zwraca limit prędkości (km/h) odcinka, na którym jest ostatnia z nich,
 * albo -1, gdy TomTom nie ma dla niego limitu. Klucz jest tylko w adresie zapytania i NIGDY nie trafia do komunikatów błędów ani do logów.
 */
public final class TomTomHttp implements TomTomLimits.Client {
  public static final String BASE_URL = "https://api.tomtom.com/";
  private static final int MAX_BODY = 64 * 1024;

  private final String key, base, userAgent;
  private final int connectMs, readMs;

  public TomTomHttp(String key) { this(key, BASE_URL, 5000, 8000, "InwokacjaLimit/1"); }

  public TomTomHttp(String key, String baseUrl, int connectMs, int readMs, String userAgent) {
    this.key = key == null ? "" : key.trim();
    this.base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    this.connectMs = connectMs; this.readMs = readMs; this.userAgent = userAgent;
  }

  public boolean hasKey() { return !key.isEmpty(); }

  @Override public int query(double[] lat, double[] lon, double[] heading) throws RoadSource.FetchException {
    if (!hasKey()) throw new RoadSource.FetchException("brak klucza TomTom");
    if (lat == null || lon == null || lat.length < 2 || lat.length != lon.length) throw new RoadSource.FetchException("za mało punktów");
    StringBuilder pts = new StringBuilder(), hds = new StringBuilder();
    boolean anyHeading = false;
    for (int i = 0; i < lat.length; i++) {
      if (i > 0) { pts.append(';'); hds.append(';'); }
      pts.append(String.format(Locale.ROOT, "%.6f,%.6f", lon[i], lat[i]));
      if (heading != null && i < heading.length && !Double.isNaN(heading[i])) {
        hds.append(Math.round(((heading[i] % 360) + 360) % 360) % 360);
        anyHeading = true;
      }
    }
    HttpURLConnection c = null;
    try {
      String url = base + "snapToRoads/1?key=" + URLEncoder.encode(key, "UTF-8") + "&points=" + pts
          + (anyHeading ? "&headings=" + hds : "")
          + "&fields=%7Broute%7Bproperties%7Bid,speedLimits%7Bvalue,unit,type%7D%7D%7D%7D";
      c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(connectMs);
      c.setReadTimeout(readMs);
      c.setUseCaches(false);
      c.setRequestProperty("User-Agent", userAgent);
      c.setRequestProperty("Accept", "application/json");
      int code = c.getResponseCode();
      if (code == 429 || code == 403 || code == 401) throw new RoadSource.FetchException("TomTom HTTP " + code, true, null);   // limit zapytań albo zły klucz: do końca doby nie pytamy
      if (code != 200) throw new RoadSource.FetchException("TomTom HTTP " + code);
      InputStream in = c.getInputStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          if (out.size() > MAX_BODY) throw new RoadSource.FetchException("odpowiedź TomTom za duża");
        }
      } finally {
        try { in.close(); } catch (IOException ignored) { /* nic */ }
      }
      return parse(new String(out.toByteArray(), "UTF-8"));
    } catch (RoadSource.FetchException e) {
      throw e;
    } catch (IOException e) {
      throw new RoadSource.FetchException("sieć TomTom: " + e.getClass().getSimpleName(), false, null);   // bez treści wyjątku: mogłaby zawierać adres z kluczem
    } catch (RuntimeException e) {
      throw new RoadSource.FetchException("błąd TomTom: " + e.getClass().getSimpleName(), false, null);
    } finally {
      if (c != null) { try { c.disconnect(); } catch (RuntimeException ignored) { /* nic */ } }
    }
  }

  /** Limit odcinka, na którym jest ostatni punkt (ostatni element trasy), albo -1. Bierze tylko limity maksymalne w km/h. */
  static int parse(String json) throws RoadSource.FetchException {
    Object root;
    try {
      root = Json.parse(json);
    } catch (RuntimeException e) {
      throw new RoadSource.FetchException("zła odpowiedź TomTom");
    }
    Map<String, Object> m = Json.obj(root);
    if (m == null) throw new RoadSource.FetchException("zła odpowiedź TomTom");
    List<Object> route = Json.arr(m.get("route"));
    if (route == null || route.isEmpty()) return -1;
    Map<String, Object> last = Json.obj(route.get(route.size() - 1));
    if (last == null) return -1;
    Map<String, Object> props = Json.obj(last.get("properties"));
    Map<String, Object> sl = props == null ? null : Json.obj(props.get("speedLimits"));
    if (sl == null) return -1;
    Object unit = sl.get("unit"), type = sl.get("type");
    if (!"kmph".equals(unit)) return -1;
    if (type != null && !"Maximum".equals(type)) return -1;      // "Recommended" to zalecenie, nie ograniczenie
    int v = (int) Math.round(Json.num(sl.get("value"), -1));
    return Maxspeed.validLimit(v) ? v : -1;
  }
}
