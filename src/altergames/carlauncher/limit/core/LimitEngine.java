package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Silnik: spina GPS, źródło dróg (sieć), pamięć trwałą i maszynę stanów.
 * Polityka danych: najpierw lokalne paczki pobierane w locie (PackSource), zapasowo internet, pamięć trwała jako awaryjna kopia.
 * Cała praca sieciowa idzie na wątku tła, a każda porażka kończy się odczekaniem i ponowną próbą, nigdy wyjątkiem.
 */
public final class LimitEngine {
  public interface Clock {
    long mono();   // czas monotoniczny (ms)
    long wall();   // czas ścienny (ms), do wieku wpisów pamięci
  }

  public static final int QUERY_RADIUS_M = 220;
  /** Ponowne zapytanie po oddaleniu się o tyle metrów od środka poprzedniego. */
  public static final double REQUERY_AFTER_M = 120;
  public static final long MIN_INTERVAL_MS = 2500;
  public static final long CACHE_MAX_AGE_MS = 30L * 24 * 3600 * 1000;
  private static final long SAVE_EVERY_MS = 30_000;

  private final Clock clock;
  private final RoadSource source;
  private final RoadCache cache;
  private final Executor bg;
  private final LimitModel model = new LimitModel();

  private List<Road> window = Collections.emptyList();
  private boolean windowValid;
  private double winLat, winLon;
  private boolean inFlight;
  private long nextAllowedAt;
  private int failures;
  private long prevRoadId = -1;
  private Fix lastFix;
  private long lastSaveAt;
  private int okQueries, failedQueries;
  private String lastError = "";

  public LimitEngine(Clock clock, RoadSource source, RoadCache cache, Executor bg) {
    this.clock = clock;
    this.source = source;
    this.cache = cache;
    this.bg = bg;
  }

  public LimitModel model() { return model; }

  /** Nowa próbka GPS (z dowolnego wątku). Nie zgłasza wyjątków. */
  public synchronized void onFix(Fix f) {
    try {
      if (f == null || !f.valid()) return;
      long now = clock.mono();
      lastFix = f;
      model.onFix(f, now);
      rematch();
      maybeRequest(f, now);
    } catch (RuntimeException e) {
      lastError = "onFix: " + e;
    }
  }

  /** Stan do wyświetlenia (wołać regularnie). Nie zgłasza wyjątków. */
  public synchronized UiState tick() {
    try {
      return model.tick(clock.mono());
    } catch (RuntimeException e) {
      lastError = "tick: " + e;
      return UiState.search();
    }
  }

  public synchronized void reset() {
    model.reset();
    lastFix = null;
    prevRoadId = -1;
  }

  public synchronized String diagnostics() {
    return "zapytania ok=" + okQueries + " błędy=" + failedQueries + " okno=" + (windowValid ? window.size() + " dróg" : "brak")
        + " pamięć=" + cache.size() + " ostatni błąd=" + lastError;
  }

  /* ---------- wewnętrzne ---------- */

  private void rematch() {
    Fix f = lastFix;
    if (f == null) return;
    List<Road> roads = roadsFor(f);
    double bearing = !Double.isNaN(f.speedKmh) && f.speedKmh >= 4 ? f.bearingDeg : Double.NaN;   // kierunek wiarygodny tylko w ruchu
    RoadMatcher.Match m = RoadMatcher.match(roads, f.lat, f.lon, bearing, f.accuracyM, prevRoadId);
    if (m == null) {
      prevRoadId = -1;
      model.onCandidate(-1, Road.Source.NONE);
    } else {
      prevRoadId = m.road.id;
      model.onCandidate(m.limit, m.source);
    }
  }

  private List<Road> roadsFor(Fix f) {
    if (windowValid && distM(f.lat, f.lon, winLat, winLon) <= QUERY_RADIUS_M - 20) return window;
    List<Road> c = cache.near(f.lat, f.lon, clock.wall(), CACHE_MAX_AGE_MS);   // awaryjnie z pamięci
    return c;
  }

  private void maybeRequest(final Fix f, final long now) {
    if (inFlight || now < nextAllowedAt) return;
    boolean covered = windowValid && distM(f.lat, f.lon, winLat, winLon) <= REQUERY_AFTER_M;
    if (covered) return;
    inFlight = true;
    nextAllowedAt = now + MIN_INTERVAL_MS;
    final double lat = f.lat, lon = f.lon;
    try {
      bg.execute(new Runnable() {
        @Override public void run() { fetchAndApply(lat, lon); }
      });
    } catch (RuntimeException e) {   // np. wykonawca zamknięty
      inFlight = false;
      lastError = "execute: " + e;
    }
  }

  private void fetchAndApply(double lat, double lon) {
    List<Road> roads = null;
    RoadSource.FetchException error = null;
    Throwable other = null;
    try {
      roads = source.fetch(lat, lon, QUERY_RADIUS_M);
    } catch (RoadSource.FetchException e) {
      error = e;
    } catch (Throwable t) {
      other = t;
    }
    boolean doSave = false;
    synchronized (this) {
      inFlight = false;
      long now = clock.mono();
      if (roads != null) {
        window = roads;
        windowValid = true;
        winLat = lat; winLon = lon;
        failures = 0;
        okQueries++;
        if (!(roads instanceof PackSource.LocalList)) cache.put(lat, lon, QUERY_RADIUS_M, roads, clock.wall());   // paczki już leżą na dysku
        if (cache.isDirty() && now - lastSaveAt > SAVE_EVERY_MS) { lastSaveAt = now; doSave = true; }
        nextAllowedAt = Math.min(nextAllowedAt, now + MIN_INTERVAL_MS);
        try { rematch(); } catch (RuntimeException e) { lastError = "rematch: " + e; }
      } else {
        failures++;
        failedQueries++;
        lastError = error != null ? error.getMessage() : String.valueOf(other);
        long backoff = Math.min(60_000L, 4000L << Math.min(failures - 1, 4));
        if (error != null && error.rateLimited) backoff = Math.max(backoff, 30_000L);
        nextAllowedAt = now + backoff;
      }
    }
    if (doSave) {
      try { cache.save(); } catch (RuntimeException ignored) { /* zapis się nie udał, spróbujemy później */ }
    }
  }

  /** Zapis pamięci na dysk (np. przy wyjściu z widoku). Można wołać z dowolnego wątku. */
  public void flush() {
    try { if (cache.isDirty()) cache.save(); } catch (RuntimeException ignored) { /* nic */ }
  }

  static double distM(double lat1, double lon1, double lat2, double lon2) {
    double dy = (lat2 - lat1) * 110540.0;
    double dx = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2)) * 111320.0;
    return Math.sqrt(dx * dx + dy * dy);
  }

  /** Pomocniczo do testów: liczba dróg w oknie. */
  public synchronized int windowSize() { return window.size(); }

  public synchronized List<Road> windowRoads() { return new ArrayList<Road>(window); }
}
