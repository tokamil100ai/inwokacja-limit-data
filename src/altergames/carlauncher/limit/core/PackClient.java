package altergames.carlauncher.limit.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.CRC32;

/**
 * Pobieranie jednego pliku (manifest albo paczka) do pliku tymczasowego, z limitami czasu i rozmiaru.
 * Nie interpretuje treści, tylko liczy rozmiar i sumę CRC32. Wszystkie porażki to FetchException.
 */
public final class PackClient {
  public static final class NotFoundException extends RoadSource.FetchException {
    public NotFoundException(String m) { super(m, false, null); }
  }

  public static final class Result {
    public final long size, crc;
    /** Pierwsze 128 bitów SHA-256 pobranej treści (32 znaki szesnastkowe). */
    public final String sha;
    Result(long size, long crc, String sha) { this.size = size; this.crc = crc; this.sha = sha; }
  }

  private final int connectMs, readMs;
  private final long totalMs;
  private final String userAgent;

  public PackClient(int connectMs, int readMs, long totalMs, String userAgent) {
    this.connectMs = connectMs; this.readMs = readMs; this.totalMs = totalMs; this.userAgent = userAgent;
  }

  public static PackClient createDefault() { return new PackClient(7000, 12000, 90_000L, "InwokacjaLimit/1"); }

  /** Pobiera url do dest (nadpisując). expectedSize < 0: rozmiar nieznany. Przy porażce usuwa dest. */
  public Result download(String url, File dest, long maxBytes, long expectedSize) throws RoadSource.FetchException {
    HttpURLConnection c = null;
    boolean ok = false;
    try {
      c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(connectMs);
      c.setReadTimeout(readMs);
      c.setUseCaches(false);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent", userAgent);
      c.setRequestProperty("Accept-Encoding", "identity");
      int code = c.getResponseCode();
      if (code == 404 || code == 410) throw new NotFoundException("HTTP " + code + " " + url);
      if (code == 429 || code == 503) throw new RoadSource.FetchException("HTTP " + code, true, null);
      if (code != 200) throw new RoadSource.FetchException("HTTP " + code);
      int declared = c.getContentLength();
      if (declared > maxBytes) throw new RoadSource.FetchException("odpowiedź za duża: " + declared);
      if (expectedSize >= 0 && declared >= 0 && declared != expectedSize) throw new RoadSource.FetchException("inny rozmiar niż w manifeście: " + declared);
      long t0 = System.nanoTime();
      CRC32 crc = new CRC32();
      java.security.MessageDigest sha = PackSigning.newSha256();
      long total = 0;
      InputStream in = c.getInputStream();
      FileOutputStream out = new FileOutputStream(dest);
      try {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
          total += n;
          if (total > maxBytes) throw new RoadSource.FetchException("odpowiedź za duża");
          if ((System.nanoTime() - t0) / 1_000_000L > totalMs) throw new RoadSource.FetchException("pobieranie trwa za długo");
          out.write(buf, 0, n);
          crc.update(buf, 0, n);
          sha.update(buf, 0, n);
        }
      } finally {
        try { in.close(); } catch (IOException ignored) { /* nic */ }
        out.close();
      }
      if (declared >= 0 && total != declared) throw new RoadSource.FetchException("ucięta odpowiedź (" + total + " z " + declared + ")");
      if (expectedSize >= 0 && total != expectedSize) throw new RoadSource.FetchException("zły rozmiar: " + total);
      ok = true;
      return new Result(total, crc.getValue(), PackSigning.shortSha256(sha.digest()));
    } catch (RoadSource.FetchException e) {
      throw e;
    } catch (IOException e) {
      throw new RoadSource.FetchException("sieć: " + e, false, e);
    } catch (RuntimeException e) {
      throw new RoadSource.FetchException("błąd: " + e, false, e);
    } finally {
      if (c != null) { try { c.disconnect(); } catch (RuntimeException ignored) { /* nic */ } }
      if (!ok) { try { dest.delete(); } catch (RuntimeException ignored) { /* nic */ } }
    }
  }
}
