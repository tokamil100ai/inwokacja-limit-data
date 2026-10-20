package altergames.carlauncher.limit.core;

import java.util.List;

/**
 * Pamięć dopasowania do drogi: trzyma obecną drogę i decyduje, kiedy ją zmienić.
 * Nad punktacją z {@link RoadMatcher} dokłada:
 * - premię dla obecnej drogi i dla drogi, która się z nią styka (ciągłość trasy; na skrzyżowaniach i wiaduktach to różni drogi),
 * - drobną premię dla drogi ze znanym limitem,
 * - zmianę drogi dopiero po {@link #CONFIRM_FIXES} odczytach z rzędu, chyba że nowa wygrywa wyraźnie albo obecna już nie pasuje,
 * - zamrożenie na postoju i przy bardzo wolnej jeździe: drgania GPS na światłach nie zmieniają drogi ani jej kierunku.
 * Nie jest bezpieczna wątkowo: woła ją {@link LimitEngine}, który synchronizuje dostęp.
 */
public final class RoadTracker {
  /** Poniżej tej prędkości dopasowania nie przeliczamy (o ile już jakieś mamy). */
  public static final double STOP_KMH = 3;
  /** Poniżej tej prędkości dopasowania nie przeliczamy, dopóki auto nie przesunie się o {@link #CREEP_M}. */
  public static final double CREEP_KMH = 12;
  public static final double CREEP_M = 10;
  /** Tyle odczytów z rzędu musi wygrywać inna droga, żeby zastąpiła obecną. */
  public static final int CONFIRM_FIXES = 2;
  /** Przewaga kosztu, przy której nowa droga zastępuje obecną od razu. */
  public static final double SWITCH_NOW_M = 12;
  /** Premia dla drogi stykającej się z obecną. */
  public static final double CONNECTED_BONUS_M = 4;
  // Dawniej była tu premia −2 m dla drogi ze znanym limitem. Usunięta 2026-10-20: dopasowanie nie może wybierać drogi dlatego, że ma limit,
  // bo wtedy ulica osiedlowa bez limitu przegrywa z sąsiednią główną i widać jej (cudzy) limit zamiast „?” (pomiar: 84,4% zamiast 90,6%
  // poprawnych odczytów przy głównej 15 m obok i GPS 10 m). Wyniki Sim dla dróg z limitami bez zmian.
  /** Dwa końce, które leżą bliżej niż tyle metrów, uznajemy za styk. */
  static final double TOUCH_M = 3;
  /** Tylu najlepszych kandydatów sprawdzamy pod kątem styku z obecną drogą. */
  private static final int TOUCH_CHECKED = 6;

  private Road current;
  private RoadMatcher.Match currentMatch;
  private long challengerId = -1;
  private int challengerCount;
  private double evalLat = Double.NaN, evalLon = Double.NaN;
  private long lastTimeMs = Long.MIN_VALUE;

  public void reset() {
    current = null;
    currentMatch = null;
    challengerId = -1;
    challengerCount = 0;
    evalLat = evalLon = Double.NaN;
    lastTimeMs = Long.MIN_VALUE;
  }

  /** Droga, na której jesteśmy, albo null. */
  public Road current() { return current; }

  /**
   * Nowa pozycja. bearingDeg ma być NaN, gdy kurs jest niewiarygodny. timeMs to czas próbki: powtórne przeliczenie tej samej próbki
   * (np. po nowym oknie dróg) nie liczy się jako kolejny odczyt do potwierdzenia zmiany drogi.
   * Zwraca dopasowanie albo null, gdy nie ma drogi w zasięgu.
   */
  public RoadMatcher.Match update(List<Road> roads, double lat, double lon, double bearingDeg,
                                  double accuracyM, double speedKmh, long timeMs) {
    boolean repeat = timeMs == lastTimeMs;
    lastTimeMs = timeMs;
    if (currentMatch != null && shouldFreeze(lat, lon, speedKmh)) return currentMatch;
    evalLat = lat; evalLon = lon;

    List<RoadMatcher.Cand> ranked = RoadMatcher.rank(roads, lat, lon, bearingDeg, accuracyM);
    if (ranked.isEmpty()) {
      reset();
      return null;
    }

    double[] adj = new double[ranked.size()];
    int bestIdx = 0, curIdx = -1;
    for (int i = 0; i < ranked.size(); i++) {
      RoadMatcher.Cand c = ranked.get(i);
      double s = c.cost;
      if (current != null) {
        if (c.road.id == current.id) { s -= RoadMatcher.PREV_BONUS_M; curIdx = i; }
        else if (i < TOUCH_CHECKED && touches(current, c.road)) s -= CONNECTED_BONUS_M;
      }
      adj[i] = s;
      if (s < adj[bestIdx]) bestIdx = i;
    }

    int pick = bestIdx;
    if (curIdx >= 0 && bestIdx != curIdx) {
      boolean clear = adj[curIdx] - adj[bestIdx] >= SWITCH_NOW_M;
      long bestId = ranked.get(bestIdx).road.id;
      if (challengerId == bestId) { if (!repeat) challengerCount++; } else { challengerId = bestId; challengerCount = 1; }
      if (!clear && challengerCount < CONFIRM_FIXES) pick = curIdx;   // jeszcze nie przekonała
    }
    if (pick != curIdx || curIdx < 0) { challengerId = -1; challengerCount = 0; }
    else if (bestIdx == curIdx) { challengerId = -1; challengerCount = 0; }

    RoadMatcher.Cand chosen = ranked.get(pick);
    current = chosen.road;
    currentMatch = RoadMatcher.resolve(chosen, bearingDeg);
    return currentMatch;
  }

  private boolean shouldFreeze(double lat, double lon, double speedKmh) {
    if (Double.isNaN(speedKmh)) return false;
    if (speedKmh < STOP_KMH) return true;
    if (speedKmh < CREEP_KMH && !Double.isNaN(evalLat)) return LimitEngine.distM(evalLat, evalLon, lat, lon) < CREEP_M;
    return false;
  }

  /** Czy dwie drogi się stykają: koniec jednej leży przy punkcie drugiej (węzeł wspólny, także w środku drogi). */
  static boolean touches(Road a, Road b) {
    return endNear(a, b) || endNear(b, a);
  }

  private static boolean endNear(Road a, Road b) {
    int[] ends = { 0, a.points() - 1 };
    for (int e : ends) {
      double la = a.lat[e], lo = a.lon[e];
      for (int i = 0; i < b.points(); i++) {
        if (LimitEngine.distM(la, lo, b.lat[i], b.lon[i]) <= TOUCH_M) return true;
      }
    }
    return false;
  }
}
