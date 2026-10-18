package altergames.carlauncher.limit.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Executor;

/**
 * Tabela mandatów aktualizowana bez aktualizacji aplikacji: kopia na dysku, odświeżana z adresu z ustawień
 * najwyżej raz na dobę. Nowa tabela wchodzi w życie tylko po pełnej walidacji (Penalties.parse) i tylko jeśli
 * jej numer wersji nie jest niższy niż obecnej. Każda porażka oznacza, że zostaje to, co jest (na końcu tabela wbudowana).
 */
public final class PenaltiesStore {
  public static final long REFRESH_MS = 24L * 3600 * 1000;
  static final long MAX_BACKOFF_MS = 6L * 3600 * 1000;
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private final File file;
  private final PackClient client;
  private final LimitEngine.Clock clock;
  private final Executor worker;
  private volatile Penalties current = Penalties.builtin();
  private volatile String url = "";
  private long lastSuccessWall;
  private long retryAt;
  private int failures;
  private boolean inFlight;
  private String lastError = "";

  public PenaltiesStore(File file, String url, PackClient client, LimitEngine.Clock clock, Executor worker) {
    this.file = file;
    this.client = client;
    this.clock = clock;
    this.worker = worker;
    this.url = url == null ? "" : url.trim();
    try {
      if (file.isFile()) {
        current = Penalties.parse(new String(FileIo.readAll(file), UTF8));
        lastSuccessWall = file.lastModified();
      }
    } catch (IOException e) {
      lastError = "odczyt: " + e;
    } catch (RuntimeException e) {
      lastError = "zapisana tabela odrzucona: " + e.getMessage();
      file.delete();
    }
  }

  /** Aktualna tabela (nigdy null). */
  public Penalties current() { return current; }

  public synchronized void setUrl(String u) {
    String n = u == null ? "" : u.trim();
    if (!n.equals(url)) { url = n; retryAt = 0; failures = 0; lastSuccessWall = 0; }
  }

  public String url() { return url; }

  public synchronized String lastError() { return lastError; }

  /** Wołać od czasu do czasu (np. przy starcie widoku): sama sprawdza, czy pora, i pracuje w tle. */
  public void refreshIfDue() {
    synchronized (this) {
      if (inFlight || url.length() == 0) return;
      long wall = clock.wall();
      boolean due = wall - lastSuccessWall > REFRESH_MS || wall < lastSuccessWall;
      if (!due || clock.mono() < retryAt) return;
      inFlight = true;
    }
    try {
      worker.execute(new Runnable() { @Override public void run() { fetch(); } });
    } catch (RuntimeException e) {
      synchronized (this) { inFlight = false; lastError = "execute: " + e; }
    }
  }

  /** Wymusza sprawdzenie (przycisk „odśwież” w ustawieniach). */
  public synchronized void forceNext() { lastSuccessWall = 0; retryAt = 0; }

  private void fetch() {
    String u = url;
    File part = new File(file.getAbsolutePath() + ".part");
    try {
      if (!u.startsWith("https://") && !u.startsWith("http://")) throw new RoadSource.FetchException("zły adres tabeli");
      File parent = file.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("nie można utworzyć katalogu tabeli");
      client.download(u, part, 70_000L, -1);
      String text = new String(FileIo.readAll(part), UTF8);
      Penalties p = Penalties.parse(text);
      synchronized (this) {
        if (p.version < current.version) throw new IllegalArgumentException("serwer podał starszą wersję tabeli (" + p.version + ")");
        if (file.exists() && !file.delete()) throw new IOException("nie można nadpisać tabeli");
        if (!part.renameTo(file)) throw new IOException("nie można zapisać tabeli");
        current = p;
        lastSuccessWall = clock.wall();
        failures = 0;
        lastError = "";
      }
    } catch (RoadSource.FetchException e) {
      fail(e.getMessage());
    } catch (IOException e) {
      fail("plik: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      fail("tabela odrzucona: " + e.getMessage());
    } catch (RuntimeException e) {
      fail("błąd: " + e);
    } finally {
      part.delete();
      synchronized (this) { inFlight = false; }
    }
  }

  private synchronized void fail(String why) {
    lastError = why;
    failures++;
    retryAt = clock.mono() + Math.min(MAX_BACKOFF_MS, 60_000L << Math.min(failures - 1, 8));
  }
}
