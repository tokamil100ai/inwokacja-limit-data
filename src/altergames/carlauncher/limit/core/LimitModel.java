package altergames.carlauncher.limit.core;

/**
 * Maszyna stanów kółka: z próbek GPS i kandydata na limit (z dopasowania do drogi) wylicza, co pokazać.
 * To jest dokładny odpowiednik logiki render() z prototypu webowego, ale deterministyczny: czas podawany z zewnątrz.
 * Wszystkie metody są synchronizowane, więc można je wołać z różnych wątków.
 */
public final class LimitModel {
  /** Nowy limit pojawia się na znaku dopiero po tylu ms stałej odpowiedzi (nic nie miga na styku dróg). */
  public static final long SHOW_DELAY_MS = 600;
  /** Krótka dziura w danych o limicie (np. brak drogi w zasięgu) nie wyrzuca znaku. */
  public static final long KEEP_MS = 3000;
  /** Gdy jesteśmy na drodze dopasowanej, ale bez limitu, stary znak znika po tak krótkim czasie jak przy zmianie limitu (nie wisi tablica z innej drogi). */
  public static final long NO_LIMIT_ROAD_KEEP_MS = SHOW_DELAY_MS;
  /** Tak długo po utracie GPS pokazujemy ostatni znany znak (tunel). Przejazd przez tunel i chwilowe zaniki nie zmieniają limitu na drodze. */
  public static final long LAST_KNOWN_MS = 60000;
  /**
   * Znak szarzeje dopiero, gdy słaby GPS (dokładność gorsza niż [WEAK_ACCURACY_M]) albo brak próbek trwa tak długo bez przerwy.
   * Wróci do koloru natychmiast, gdy tylko pozycja znów jest dobra: chcemy kolorowego znaku, gdy mamy zaufane dane.
   */
  public static final long GRAY_HOLD_MS = 10000;
  /** Brak nowej próbki GPS przez tyle ms oznacza utratę GPS. */
  public static final long GPS_LOST_MS = 4000;
  /** Dokładność gorsza niż tyle metrów oznacza słaby GPS. */
  public static final double WEAK_ACCURACY_M = 35;
  /** Poniżej tej prędkości pokazujemy 0 (jak w oryginalnej aplikacji): drgania GPS na postoju nie mają skakać po cyfrach. */
  public static final int MIN_SHOWN_KMH = 5;
  /** Duże przekroczenie (czerwona poświata pod znakiem): włącza się od tylu km/h ponad limit, gaśnie poniżej drugiej wartości (histereza). */
  public static final int SEVERE_ON_KMH = 30, SEVERE_OFF_KMH = 27;

  /** Ustawienia logiki (tolerancja, szacunki, progi czasowe, poświata); wartości domyślne to stałe powyżej. */
  private LimitConfig cfg = new LimitConfig();
  /** Czy dane drogowe są "na żywo": jest internet i paczki były niedawno sprawdzone na serwerze. Bez tego znak ma pomarańczowy pierścień (chyba że limit przyszedł z TomTom). */
  private boolean dataLive = true;
  private boolean severe;
  private Penalties penalties = Penalties.builtin();

  private boolean haveFix;
  private long lastFixAt;
  private double accuracy = Double.NaN;
  private long weakSince;        // od kiedy dokładność jest słaba bez przerwy (0 = nie jest)
  private double speed;          // km/h po filtrze
  private boolean speedKnown;
  private long lastSpeedAt;
  private int glitches;

  private int candLimit = -1;
  private Road.Source candKind = Road.Source.NONE;
  private boolean candOnRoad;
  private boolean candLive, shownLive, goodLive;
  private int shownLimit = -1;
  private Road.Source shownKind = Road.Source.NONE;
  private int pendLimit = -1;
  private Road.Source pendKind = Road.Source.NONE;
  private long pendAt;
  private long seenAt = Long.MIN_VALUE / 2;
  private int goodLimit = -1;
  private Road.Source goodKind = Road.Source.NONE;
  private long goodAt = Long.MIN_VALUE / 2;

  public synchronized void configure(int tol, boolean showFine) {
    LimitConfig c = cfg.copy();
    c.tol = tol; c.showFine = showFine;
    cfg = c.clamped();
  }

  /** Wszystkie ustawienia naraz (z ustawień aplikacji); model robi własną, obciętą do zakresów kopię. */
  public synchronized void configure(LimitConfig c) { if (c != null) cfg = c.clamped(); }

  /** Kopia bieżących ustawień (do testów i diagnostyki). */
  public synchronized LimitConfig config() { return cfg.copy(); }

  /** Jak wyżej, z przełącznikiem limitów szacowanych (ustawienie widżetu: pokazywać przerywany pierścień czy wolimy "?"). */
  public synchronized void configure(int tol, boolean showFine, boolean useEstimates) {
    configure(tol, showFine, useEstimates, true);
  }

  /** Jak wyżej, z osobnym przełącznikiem ostrzeżeń (mandat, czerwona poświata) przy limitach szacowanych. */
  public synchronized void configure(int tol, boolean showFine, boolean useEstimates, boolean alarmsForEstimates) {
    LimitConfig c = cfg.copy();
    c.tol = tol; c.showFine = showFine; c.useEstimates = useEstimates; c.alarmsForEstimates = alarmsForEstimates;
    cfg = c.clamped();
  }

  public synchronized void setPenalties(Penalties p) { if (p != null) this.penalties = p; }

  /** Limit aktualnie pokazywany na znaku albo -1 (dla innych funkcji, np. ostrzegania o fotoradarach). */
  public synchronized int shownLimit() { return shownLimit; }

  /** Czy dane drogowe są teraz "na żywo" (patrz pole dataLive). Wołać przy zmianie stanu sieci. */
  public synchronized void setDataLive(boolean live) { this.dataLive = live; }

  public synchronized void reset() {
    haveFix = false;
    weakSince = 0;
    severe = false;
    speed = 0; speedKnown = false; glitches = 0;
    candLimit = -1; candKind = Road.Source.NONE; candOnRoad = false; candLive = false; shownLive = false; goodLive = false;
    shownLimit = -1; shownKind = Road.Source.NONE; pendLimit = -1; pendKind = Road.Source.NONE;
    seenAt = Long.MIN_VALUE / 2; goodAt = Long.MIN_VALUE / 2; goodLimit = -1;
  }

  /** Nowa próbka GPS. now to czas monotoniczny w ms. */
  public synchronized void onFix(Fix f, long now) {
    if (f == null || !f.valid()) return;
    haveFix = true;
    lastFixAt = now;
    accuracy = f.accuracyM;
    boolean weakNow = !Double.isNaN(accuracy) && accuracy > cfg.weakAccuracyM;
    if (!weakNow) weakSince = 0; else if (weakSince == 0) weakSince = now;
    double v = f.speedKmh;
    boolean ok = !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0 && v <= 350;
    if (!ok) { speedKnown = false; return; }
    double dt = Math.max(0.2, Math.min(5.0, (now - lastSpeedAt) / 1000.0));
    // odrzucenie nierealnych skoków (np. pierwsze próbki po tunelu): najwyżej 2 próbki z rzędu
    if (speedKnown && Math.abs(v - speed) > 15 + 40 * dt && glitches < 2) {
      glitches++;
    } else {
      speed = v;
      glitches = 0;
    }
    speedKnown = true;
    lastSpeedAt = now;
  }

  /** Limit z dopasowania do drogi dla ostatniej pozycji: limit w km/h albo -1 gdy droga nie ma limitu lub brak drogi. */
  public synchronized void onCandidate(int limit, Road.Source kind) { onCandidate(limit, kind, false); }

  /** To samo, z informacją, czy w ogóle jesteśmy na dopasowanej drodze (droga bez limitu) czy nie ma żadnej drogi w zasięgu. */
  public synchronized void onCandidate(int limit, Road.Source kind, boolean onRoad) { onCandidate(limit, kind, onRoad, false); }

  /** To samo, z informacją, czy limit pochodzi z odpowiedzi na żywo (TomTom): taki znak nie ma pomarańczowego pierścienia nawet bez sieci lokalnej. */
  public synchronized void onCandidate(int limit, Road.Source kind, boolean onRoad, boolean live) {
    candOnRoad = onRoad;
    candLive = live;
    if (!cfg.useEstimates && kind == Road.Source.ESTIMATE) { limit = -1; kind = Road.Source.NONE; }   // szacunek wyłączony: droga bez limitu
    if (!Maxspeed.validLimit(limit) || kind == null || kind == Road.Source.NONE) { candLimit = -1; candKind = Road.Source.NONE; }   // tylko poprawne limity 5..140
    else { candLimit = limit; candKind = kind; }
  }

  public synchronized UiState tick(long now) {
    boolean gpsNone = !haveFix || ageOf(now, lastFixAt) > GPS_LOST_MS;
    UiState.View view;
    int limit = -1;
    boolean estimated = false, offline = false;

    if (gpsNone) {
      if (goodLimit >= 0 && ageOf(now, goodAt) < cfg.lastKnownMs) {
        view = UiState.View.TUNNEL; limit = goodLimit; estimated = goodKind == Road.Source.ESTIMATE; offline = cfg.offlineMark && !(goodLive || dataLive);
      } else {
        view = UiState.View.SEARCH;
        shownLimit = -1; pendLimit = -1; goodLimit = -1;
      }
    } else {
      if (candLimit >= 0) {
        seenAt = now;
        if (shownLimit < 0) { shownLimit = candLimit; shownKind = candKind; shownLive = candLive; pendLimit = -1; }
        else if (shownLimit != candLimit) {
          if (cfg.showDelayMs <= 0) { shownLimit = candLimit; shownKind = candKind; shownLive = candLive; pendLimit = -1; }
          else if (pendLimit != candLimit) { pendLimit = candLimit; pendKind = candKind; pendAt = now; }
          else if (ageOf(now, pendAt) >= cfg.showDelayMs) {
            shownLimit = candLimit; shownKind = candKind; shownLive = candLive; pendLimit = -1;
          }
        } else { pendLimit = -1; shownKind = candKind; shownLive = candLive; }
      } else if (!(shownLimit >= 0 && ageOf(now, seenAt) < (candOnRoad ? Math.min(cfg.keepMs, cfg.showDelayMs) : cfg.keepMs))) {
        shownLimit = -1; pendLimit = -1;
      }
      if (shownLimit >= 0) {
        limit = shownLimit; estimated = shownKind == Road.Source.ESTIMATE; offline = cfg.offlineMark && !(shownLive || dataLive);
        goodLimit = shownLimit; goodKind = shownKind; goodLive = shownLive; goodAt = now;
        boolean weak = weakSince != 0 && ageOf(now, weakSince) >= cfg.grayHoldMs;      // dopiero po czasie szarości (domyślnie GRAY_HOLD_MS) słabego GPS
        view = weak ? UiState.View.WEAK : UiState.View.DRIVE;
      } else {
        view = UiState.View.FREE;
      }
    }

    boolean showSpeed = view == UiState.View.DRIVE || view == UiState.View.WEAK || view == UiState.View.FREE;
    int spd = showSpeed ? displaySpeed(speed, cfg.minShownKmh) : -1;
    int fine = 0, points = 0;
    boolean active = view == UiState.View.DRIVE;
    int excess = active && speedKnown ? displaySpeed(speed, cfg.minShownKmh) - limit : 0;
    boolean muted = estimated && !cfg.alarmsForEstimates;                   // limit szacowany bez ostrzeżeń: tylko znak
    if (active && cfg.glow && excess >= cfg.glowOnKmh && !muted) severe = true;
    else if (!active || muted || !cfg.glow || excess < cfg.glowOffKmh()) severe = false;
    if (active && speedKnown && !muted) {
      int eff = excess - cfg.tol;
      if (cfg.showFine && eff > 0) {
        Penalties.Band b = penalties.find(eff);
        if (b != null) { fine = b.fine; points = b.points; }
      }
    }
    boolean stale = view == UiState.View.WEAK || (view == UiState.View.TUNNEL && ageOf(now, lastFixAt) >= cfg.grayHoldMs);
    return new UiState(view, limit, estimated, spd, severe, fine, points, stale, offline, muted && active);
  }

  /**
   * Prędkość pokazywana kierowcy: poniżej {@link #MIN_SHOWN_KMH} zero, od niej zwykłe zaokrąglenie do pełnych km/h (jak w Velociraptorze;
   * oryginalna aplikacja obcina ułamki). Tę samą liczbę widać w kółku i ona liczy się do koloru kółka, opaski z mandatem i czerwonej poświaty.
   */
  public static int displaySpeed(double kmh) { return displaySpeed(kmh, MIN_SHOWN_KMH); }

  /** To samo z progiem z ustawień ([LimitConfig#minShownKmh]). */
  public static int displaySpeed(double kmh, int minShown) {
    if (Double.isNaN(kmh) || kmh < minShown) return 0;
    return (int) Math.round(kmh);
  }

  private static long ageOf(long now, long then) { return Math.max(0, now - then); }

  private static double clamp(double v, double lo, double hi) {
    if (Double.isNaN(v)) return lo;
    return Math.min(hi, Math.max(lo, v));
  }

}
