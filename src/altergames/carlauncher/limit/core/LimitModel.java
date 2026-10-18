package altergames.carlauncher.limit.core;

/**
 * Maszyna stanów kółka: z próbek GPS i kandydata na limit (z dopasowania do drogi) wylicza, co pokazać.
 * To jest dokładny odpowiednik logiki render() z prototypu webowego, ale deterministyczny: czas podawany z zewnątrz.
 * Wszystkie metody są synchronizowane, więc można je wołać z różnych wątków.
 */
public final class LimitModel {
  /** Nowy limit pojawia się na znaku dopiero po tylu ms stałej odpowiedzi (nic nie miga na styku dróg). */
  public static final long SHOW_DELAY_MS = 600;
  /** Krótka dziura w danych o limicie nie wyrzuca znaku. */
  public static final long KEEP_MS = 3000;
  /** Tak długo po utracie GPS pokazujemy ostatni znany znak (tunel). */
  public static final long LAST_KNOWN_MS = 15000;
  /** Brak nowej próbki GPS przez tyle ms oznacza utratę GPS. */
  public static final long GPS_LOST_MS = 4000;
  /** Dokładność gorsza niż tyle metrów oznacza słaby GPS. */
  public static final double WEAK_ACCURACY_M = 35;
  /** Obwód pokazuje prędkość do tylu razy limitu. */
  public static final double GAUGE_FULL = 1.25;

  private int tol = 3;
  private int redAt = 20;
  private boolean showFine = true;
  private Penalties penalties = Penalties.builtin();

  private boolean haveFix;
  private long lastFixAt;
  private double accuracy = Double.NaN;
  private double speed;          // km/h po filtrze
  private boolean speedKnown;
  private long lastSpeedAt;
  private int glitches;

  private int candLimit = -1;
  private Road.Source candKind = Road.Source.NONE;
  private int shownLimit = -1;
  private Road.Source shownKind = Road.Source.NONE;
  private int pendLimit = -1;
  private Road.Source pendKind = Road.Source.NONE;
  private long pendAt;
  private long seenAt = Long.MIN_VALUE / 2;
  private int goodLimit = -1;
  private Road.Source goodKind = Road.Source.NONE;
  private long goodAt = Long.MIN_VALUE / 2;

  public synchronized void configure(int tol, int redAt, boolean showFine) {
    this.tol = Math.max(0, Math.min(10, tol));
    this.redAt = Math.max(6, Math.min(40, redAt));
    this.showFine = showFine;
  }

  public synchronized void setPenalties(Penalties p) { if (p != null) this.penalties = p; }

  public synchronized void reset() {
    haveFix = false;
    speed = 0; speedKnown = false; glitches = 0;
    candLimit = -1; candKind = Road.Source.NONE;
    shownLimit = -1; shownKind = Road.Source.NONE; pendLimit = -1; pendKind = Road.Source.NONE;
    seenAt = Long.MIN_VALUE / 2; goodAt = Long.MIN_VALUE / 2; goodLimit = -1;
  }

  /** Nowa próbka GPS. now to czas monotoniczny w ms. */
  public synchronized void onFix(Fix f, long now) {
    if (f == null || !f.valid()) return;
    haveFix = true;
    lastFixAt = now;
    accuracy = f.accuracyM;
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
  public synchronized void onCandidate(int limit, Road.Source kind) {
    if (!Maxspeed.validLimit(limit) || kind == null || kind == Road.Source.NONE) { candLimit = -1; candKind = Road.Source.NONE; }   // tylko poprawne limity 5..140
    else { candLimit = limit; candKind = kind; }
  }

  public synchronized UiState tick(long now) {
    boolean gpsNone = !haveFix || ageOf(now, lastFixAt) > GPS_LOST_MS;
    UiState.View view;
    int limit = -1;
    boolean estimated = false;

    if (gpsNone) {
      if (goodLimit >= 0 && ageOf(now, goodAt) < LAST_KNOWN_MS) {
        view = UiState.View.TUNNEL; limit = goodLimit; estimated = goodKind == Road.Source.ESTIMATE;
      } else {
        view = UiState.View.SEARCH;
        shownLimit = -1; pendLimit = -1; goodLimit = -1;
      }
    } else {
      if (candLimit >= 0) {
        seenAt = now;
        if (shownLimit < 0) { shownLimit = candLimit; shownKind = candKind; pendLimit = -1; }
        else if (shownLimit != candLimit) {
          if (pendLimit != candLimit) { pendLimit = candLimit; pendKind = candKind; pendAt = now; }
          else if (ageOf(now, pendAt) >= SHOW_DELAY_MS) {
            shownLimit = candLimit; shownKind = candKind; pendLimit = -1;
          }
        } else { pendLimit = -1; shownKind = candKind; }
      } else if (!(shownLimit >= 0 && ageOf(now, seenAt) < KEEP_MS)) {
        shownLimit = -1; pendLimit = -1;
      }
      if (shownLimit >= 0) {
        limit = shownLimit; estimated = shownKind == Road.Source.ESTIMATE;
        goodLimit = shownLimit; goodKind = shownKind; goodAt = now;
        boolean weak = !Double.isNaN(accuracy) && accuracy > WEAK_ACCURACY_M;
        view = weak ? UiState.View.WEAK : UiState.View.DRIVE;
      } else {
        view = UiState.View.FREE;
      }
    }

    boolean showSpeed = view == UiState.View.DRIVE || view == UiState.View.WEAK || view == UiState.View.FREE;
    int spd = showSpeed ? (int) Math.round(speed) : -1;
    float over = 0f, gauge = 0f;
    int fine = 0, points = 0;
    if (view == UiState.View.DRIVE || view == UiState.View.WEAK) {
      gauge = (float) clamp(speed / (limit * GAUGE_FULL), 0, 1);
    }
    if (view == UiState.View.DRIVE && speedKnown) {
      int excess = (int) Math.round(speed) - limit;
      double half = redAt / 2.0;
      over = (float) clamp((excess - half) / half, 0, 1);
      int eff = excess - tol;
      if (showFine && eff > 0) {
        Penalties.Band b = penalties.find(eff);
        if (b != null) { fine = b.fine; points = b.points; }
      }
    }
    boolean stale = view == UiState.View.WEAK || view == UiState.View.TUNNEL;
    return new UiState(view, limit, estimated, spd, round3(over), round3(gauge), fine, points, stale);
  }

  private static long ageOf(long now, long then) { return Math.max(0, now - then); }

  private static double clamp(double v, double lo, double hi) {
    if (Double.isNaN(v)) return lo;
    return Math.min(hi, Math.max(lo, v));
  }

  private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
}
