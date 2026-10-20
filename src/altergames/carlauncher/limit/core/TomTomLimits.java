package altergames.carlauncher.limit.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Warstwa limitów z TomTom (tylko online, wyniki wyłącznie w pamięci RAM, nic nie trafia na dysk: warunki TomTom co do przechowywania
 * wyników są niepewne). Dla każdej drogi, po której jedziemy (numer drogi z dopasowania), pyta o limit, gdy zebraliśmy co najmniej dwie
 * pozycje na tej drodze, i trzyma odpowiedź w pamięci: ta sama droga nie jest pytana ponownie, dopóki odpowiedź jest świeża
 * ([TTL_MS], a na długim odcinku co [REFRESH_M]). Pilnuje dziennego limitu zapytań, przerwy między zapytaniami i odczekania po błędach;
 * po błędzie 429/403 (limit albo klucz) milknie do końca doby UTC. Nigdy nie zgłasza wyjątków.
 * Limit z TomTom wygląda i działa jak limit zapisany w OSM (decyzja Kamila); w którym miejscu ma pierwszeństwo, ustala [Mode].
 */
public final class TomTomLimits {
  /** Skąd silnik bierze limit, gdy są dwa źródła. */
  public enum Mode {
    /** TomTom nadpisuje OSM, gdy jego odpowiedź dojdzie (do tego czasu pokazujemy OSM); bez odpowiedzi zostaje OSM. */
    TOMTOM_FIRST,
    /** Limit zapisany w OSM ma pierwszeństwo; TomTom uzupełnia drogi bez jawnego limitu (także te z samym szacunkiem). */
    OSM_FIRST,
    /** Gdy oba źródła mają limit, pokazujemy niższy (zgodnie z "wolę być odstraszany"). */
    LOWER,
    /** TomTom wyłączony: nic nie jest pytane. */
    OSM_ONLY;

    public static Mode fromInt(int i) {
      Mode[] v = values();
      return i >= 0 && i < v.length ? v[i] : TOMTOM_FIRST;
    }
  }

  /** Wynik wyboru źródła: limit i jego rodzaj (limit z TomTom jest traktowany jak znak, nigdy jak szacunek). */
  public static final class Chosen {
    public final int limit;
    public final Road.Source source;
    /** Limit z odpowiedzi TomTom (na żywo). */
    public final boolean live;
    Chosen(int limit, Road.Source source, boolean live) { this.limit = limit; this.source = source; this.live = live; }
  }

  /** Zapytanie do TomTom: limit odcinka ostatniego punktu albo -1. Zgłasza FetchException (rateLimited = limit zapytań albo klucz). */
  public interface Client {
    int query(double[] lat, double[] lon, double[] heading) throws RoadSource.FetchException;
  }

  /** Czy teraz wolno pytać (np. jest internet i funkcja włączona). Wołane z wątku GPS, ma być szybkie. */
  public interface Gate { boolean allowed(); }

  /** Licznik zapytań na dobę UTC, trwały między uruchomieniami (w aplikacji w ustawieniach). */
  public interface Usage {
    int get(long day);
    void set(long day, int count);
  }

  public static final int DAILY_LIMIT = 2000;
  public static final long TTL_MS = 20L * 60 * 1000, NEGATIVE_TTL_MS = 5L * 60 * 1000, MIN_GAP_MS = 4000, MAX_BACKOFF_MS = 10L * 60 * 1000;
  public static final double POINT_SPACING_M = 12, REFRESH_M = 1500;
  static final int MAX_POINTS = 6, MIN_POINTS_ON_ROAD = 2, MAX_CACHE = 512;
  private static final long DAY_MS = 24L * 3600 * 1000;

  private static final class Cached {
    final int limit; final long at; final double travelledAt;
    Cached(int limit, long at, double travelledAt) { this.limit = limit; this.at = at; this.travelledAt = travelledAt; }
  }

  private final Client client;
  private final LimitEngine.Clock clock;
  private final Executor bg;
  private final Gate gate;
  private final Usage usage;
  private int dailyLimit = DAILY_LIMIT;

  private final double[] pLat = new double[MAX_POINTS], pLon = new double[MAX_POINTS], pHead = new double[MAX_POINTS];
  private int pCount;
  private double travelled;
  private long lastRoadId;
  private int pointsOnRoad;
  private final LinkedHashMap<Long, Cached> cache = new LinkedHashMap<Long, Cached>(64, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long, Cached> e) { return size() > MAX_CACHE; }
  };
  private boolean inFlight;
  private long nextAllowedAt;
  private int failures;
  private long exhaustedDay = Long.MIN_VALUE;
  private int ok, failed, noData;
  private String lastError = "";
  private final Map<Long, Integer> memoryUsage = new HashMap<Long, Integer>();

  public TomTomLimits(Client client, LimitEngine.Clock clock, Executor bg, Gate gate, Usage usage) {
    this.client = client; this.clock = clock; this.bg = bg; this.gate = gate;
    this.usage = usage != null ? usage : new Usage() {
      @Override public int get(long day) { Integer v = memoryUsage.get(day); return v == null ? 0 : v; }
      @Override public void set(long day, int count) { memoryUsage.clear(); memoryUsage.put(day, count); }
    };
  }

  /** Do testów: niższy dzienny limit zapytań. */
  /** Dzienny limit zapytań (ustawienie w aplikacji; 100 do 2000). */
  public synchronized void setDailyLimit(int n) { dailyLimit = Math.max(1, n); }

  /**
   * Pozycja z numerem dopasowanej drogi (0 = brak drogi). Zapamiętuje punkty śladu i, gdy trzeba, zleca zapytanie w tle.
   * Wołać na każdej próbce GPS; bezpieczne z dowolnego wątku.
   */
  public synchronized void onPosition(long roadId, Fix f) {
    try {
      long now = clock.mono();
      if (pCount == 0 || LimitEngine.distM(pLat[pCount - 1], pLon[pCount - 1], f.lat, f.lon) >= POINT_SPACING_M) {
        if (pCount > 0) travelled += LimitEngine.distM(pLat[pCount - 1], pLon[pCount - 1], f.lat, f.lon);
        push(f);
        if (roadId == lastRoadId) pointsOnRoad++;
      }
      if (roadId != lastRoadId) { lastRoadId = roadId; pointsOnRoad = roadId == 0 ? 0 : 1; }
      if (roadId == 0 || inFlight || pointsOnRoad < MIN_POINTS_ON_ROAD || pCount < 2) return;
      Cached e = cache.get(roadId);
      if (e != null && now - e.at < (e.limit > 0 ? TTL_MS : NEGATIVE_TTL_MS) && travelled - e.travelledAt < REFRESH_M) return;   // odpowiedź jeszcze świeża
      if (now < nextAllowedAt) return;
      long day = clock.wall() / DAY_MS;
      if (day == exhaustedDay) return;
      if (gate != null && !gate.allowed()) return;
      int used = usage.get(day);
      if (used >= dailyLimit) return;
      startQuery(roadId, now, day, used);
    } catch (RuntimeException ex) {
      lastError = "onPosition: " + ex.getClass().getSimpleName();
    }
  }

  private void push(Fix f) {
    if (pCount == MAX_POINTS) {
      System.arraycopy(pLat, 1, pLat, 0, MAX_POINTS - 1);
      System.arraycopy(pLon, 1, pLon, 0, MAX_POINTS - 1);
      System.arraycopy(pHead, 1, pHead, 0, MAX_POINTS - 1);
      pCount--;
    }
    pLat[pCount] = f.lat; pLon[pCount] = f.lon;
    pHead[pCount] = !Double.isNaN(f.speedKmh) && f.speedKmh >= 4 ? f.bearingDeg : Double.NaN;
    pCount++;
  }

  private void startQuery(final long roadId, long now, long day, int used) {
    final double[] la = new double[pCount], lo = new double[pCount], hd = new double[pCount];
    System.arraycopy(pLat, 0, la, 0, pCount);
    System.arraycopy(pLon, 0, lo, 0, pCount);
    System.arraycopy(pHead, 0, hd, 0, pCount);
    final double travelledNow = travelled;
    inFlight = true;
    nextAllowedAt = now + MIN_GAP_MS;
    usage.set(day, used + 1);
    try {
      bg.execute(new Runnable() {
        @Override public void run() {
          int limit = -1;
          RoadSource.FetchException error = null;
          Throwable other = null;
          try {
            limit = client.query(la, lo, hd);
          } catch (RoadSource.FetchException e) {
            error = e;
          } catch (Throwable t) {
            other = t;
          }
          finish(roadId, limit, error, other, travelledNow);
        }
      });
    } catch (RuntimeException e) {          // np. wykonawca zamknięty
      inFlight = false;
      lastError = "execute: " + e.getClass().getSimpleName();
    }
  }

  private synchronized void finish(long roadId, int limit, RoadSource.FetchException error, Throwable other, double travelledAt) {
    inFlight = false;
    long now = clock.mono();
    if (error == null && other == null) {
      failures = 0;
      cache.put(roadId, new Cached(limit, now, travelledAt));
      if (limit > 0) ok++; else noData++;
      return;
    }
    failed++;
    failures++;
    lastError = error != null ? error.getMessage() : String.valueOf(other.getClass().getSimpleName());
    long backoff = Math.min(MAX_BACKOFF_MS, 15_000L << Math.min(failures - 1, 6));
    nextAllowedAt = now + backoff;
    if (error != null && error.rateLimited) exhaustedDay = clock.wall() / DAY_MS;   // do końca doby UTC traktujemy jak brak TomTom
  }

  /** Świeży limit z TomTom dla drogi albo -1 (brak odpowiedzi, brak danych u TomTom albo odpowiedź zbyt stara). */
  public synchronized int limitFor(long roadId) {
    Cached e = cache.get(roadId);
    if (e == null || e.limit <= 0) return -1;
    return clock.mono() - e.at < 3 * TTL_MS ? e.limit : -1;
  }

  public synchronized int usedToday() { return usage.get(clock.wall() / DAY_MS); }

  public synchronized boolean exhausted() { return clock.wall() / DAY_MS == exhaustedDay; }

  public synchronized String diagnostics() {
    return "TomTom: ok=" + ok + " bez limitu=" + noData + " błędy=" + failed + " dziś=" + usage.get(clock.wall() / DAY_MS) + "/" + dailyLimit
        + (exhausted() ? " (wyczerpany do końca doby)" : "") + " ostatni błąd=" + lastError;
  }

  /** Wybór limitu z dwóch źródeł według trybu (patrz [Mode]). osm* to wynik dopasowania do drogi, tt to limit z TomTom albo -1. */
  public static Chosen combine(Mode mode, int osmLimit, Road.Source osmKind, int tt) {
    Chosen osm = new Chosen(osmLimit, osmKind, false);
    if (mode == null || mode == Mode.OSM_ONLY || tt <= 0) return osm;
    Chosen fromTt = new Chosen(tt, Road.Source.SIGN, true);
    switch (mode) {
      case TOMTOM_FIRST:
        return fromTt;
      case OSM_FIRST:
        return osmLimit > 0 && osmKind == Road.Source.SIGN ? osm : fromTt;
      case LOWER:
        if (osmLimit <= 0) return fromTt;
        return tt < osmLimit ? fromTt : osm;
      default:
        return osm;
    }
  }
}
