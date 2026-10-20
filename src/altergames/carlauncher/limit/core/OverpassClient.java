package altergames.carlauncher.limit.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

/**
 * Klient Overpass API. Zawsze z limitami czasu i rozmiaru odpowiedzi, z awaryjnym drugim serwerem.
 * Działa tak samo na Androidzie i na komputerze (tylko java.net), więc da się go przetestować lokalnym serwerem.
 */
public final class OverpassClient implements RoadSource {
  public static final String[] DEFAULT_ENDPOINTS = {
      "https://overpass.openstreetmap.fr/api/interpreter",
      "https://overpass-api.de/api/interpreter",
      "https://overpass.private.coffee/api/interpreter",
      "https://overpass.kumi.systems/api/interpreter",
  };
  static final int MAX_BODY = 3_000_000;
  static final String TYPES = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|"
      + "tertiary|tertiary_link|unclassified|residential|living_street|service";

  private final String[] endpoints;
  private final int connectMs;
  private final int readMs;
  private final long budgetMs;
  private final String userAgent;
  private int next;   // który serwer próbować najpierw (rotacja po porażkach)

  public OverpassClient(String[] endpoints, int connectMs, int readMs, long budgetMs, String userAgent) {
    this.endpoints = endpoints == null || endpoints.length == 0 ? DEFAULT_ENDPOINTS : endpoints.clone();
    this.connectMs = connectMs;
    this.readMs = readMs;
    this.budgetMs = budgetMs;
    this.userAgent = userAgent;
  }

  public static OverpassClient createDefault() {
    return new OverpassClient(DEFAULT_ENDPOINTS, 4000, 9000, 14000, "InwokacjaLauncher-LimitWidget/1.0 (osobisty)");
  }

  /** Zapytanie Overpass QL. Współrzędne zawsze z kropką dziesiętną, niezależnie od języka telefonu. */
  public static String buildQuery(double lat, double lon, int radiusM) {
    int r = Math.max(50, Math.min(500, radiusM));
    return String.format(Locale.US,
        "[out:json][timeout:8];way(around:%d,%.6f,%.6f)[\"highway\"~\"^(%s)$\"];out tags geom;", r, lat, lon, TYPES);
  }

  @Override
  public List<Road> fetch(double lat, double lon, int radiusM) throws FetchException {
    if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      throw new FetchException("zła pozycja");
    }
    long start = System.currentTimeMillis();
    String q = buildQuery(lat, lon, radiusM);
    FetchException last = null;
    int attempts = Math.min(2, endpoints.length);
    for (int a = 0; a < attempts; a++) {
      if (a > 0 && System.currentTimeMillis() - start > budgetMs) break;
      String ep;
      synchronized (this) { ep = endpoints[(next + a) % endpoints.length]; }
      try {
        List<Road> r = once(ep, q);
        synchronized (this) { next = (next + a) % endpoints.length; }   // zostajemy przy działającym serwerze
        return r;
      } catch (FetchException e) {
        last = e;
        if (e.rateLimited) break;   // serwer prosi o spokój, nie męczymy drugiego w tej samej sekundzie
      }
    }
    synchronized (this) { next = (next + 1) % endpoints.length; }
    throw last != null ? last : new FetchException("brak serwera");
  }

  private List<Road> once(String endpoint, String query) throws FetchException {
    HttpURLConnection c = null;
    try {
      URL url = new URL(endpoint + "?data=" + URLEncoder.encode(query, "UTF-8"));
      c = (HttpURLConnection) url.openConnection();
      c.setConnectTimeout(connectMs);
      c.setReadTimeout(readMs);
      c.setRequestMethod("GET");
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", userAgent);
      c.setRequestProperty("Accept", "application/json");
      int code = c.getResponseCode();
      if (code == 429) throw new FetchException("HTTP 429", true, null);
      if (code != 200) throw new FetchException("HTTP " + code);
      String body = readLimited(c.getInputStream());
      try {
        return OverpassParser.parse(body);
      } catch (Json.JsonException e) {
        throw new FetchException("zła odpowiedź: " + e.getMessage(), false, e);
      }
    } catch (FetchException e) {
      throw e;
    } catch (IOException e) {
      throw new FetchException("sieć: " + e.getClass().getSimpleName() + " " + e.getMessage(), false, e);
    } catch (RuntimeException e) {
      throw new FetchException("błąd: " + e.getClass().getSimpleName(), false, e);
    } finally {
      if (c != null) { try { c.disconnect(); } catch (RuntimeException ignored) { /* nic */ } }
    }
  }

  private static String readLimited(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
      byte[] buf = new byte[16 * 1024];
      int n, total = 0;
      while ((n = in.read(buf)) > 0) {
        total += n;
        if (total > MAX_BODY) throw new IOException("odpowiedź za duża");
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), "UTF-8");
    } finally {
      try { in.close(); } catch (IOException ignored) { /* nic */ }
    }
  }
}
