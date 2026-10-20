package altergames.carlauncher.limit.core;

/**
 * Ustawienia logiki trybu "Limit prędkości" (strona Ustawienia > Limit prędkości w aplikacji, klucze limit_* w Data).
 * Zwykła klasa z polami publicznymi: aplikacja buduje obiekt z zapisanych ustawień i przekazuje go do [LimitModel#configure(LimitConfig)],
 * model robi własną kopię. Wartości domyślne to dotychczasowe stałe, więc bez ustawień nic się nie zmienia.
 * Wygląd (kolory, rozmiary) jest osobno w [Painter.Style].
 */
public final class LimitConfig {
  /** Tolerancja pomiaru (0..10 km/h) i pokazywanie mandatu z punktami. */
  public int tol = 3;
  public boolean showFine = true;
  /** Limity szacowane z typu drogi (przerywany pierścień) i ostrzeżenia (mandat, poświata) przy nich. */
  public boolean useEstimates = true;
  public boolean alarmsForEstimates = true;
  /** Pomarańczowy znacznik dla danych bez potwierdzenia na żywo (bez internetu). */
  public boolean offlineMark = true;
  /** Czerwona poświata przy dużym przekroczeniu: włączenie i próg (gaśnie 3 km/h niżej). */
  public boolean glow = true;
  public int glowOnKmh = 30;
  /** Nowy limit utrzymuje się tyle ms, zanim znak się zmieni. */
  public long showDelayMs = LimitModel.SHOW_DELAY_MS;
  /** Znak zostaje tyle ms, gdy droga nie ma danych o limicie. */
  public long keepMs = LimitModel.KEEP_MS;
  /** Ostatni znak po utracie GPS (tunel). */
  public long lastKnownMs = LimitModel.LAST_KNOWN_MS;
  /** Szarość po tylu ms słabego GPS. */
  public long grayHoldMs = LimitModel.GRAY_HOLD_MS;
  /** Poniżej tej prędkości pokazujemy 0. */
  public int minShownKmh = LimitModel.MIN_SHOWN_KMH;
  /** Dokładność GPS gorsza niż tyle metrów to słaby GPS. */
  public double weakAccuracyM = LimitModel.WEAK_ACCURACY_M;

  public int glowOffKmh() { return Math.max(0, glowOnKmh - 3); }

  public LimitConfig copy() {
    LimitConfig c = new LimitConfig();
    c.tol = tol; c.showFine = showFine; c.useEstimates = useEstimates; c.alarmsForEstimates = alarmsForEstimates; c.offlineMark = offlineMark;
    c.glow = glow; c.glowOnKmh = glowOnKmh; c.showDelayMs = showDelayMs; c.keepMs = keepMs; c.lastKnownMs = lastKnownMs; c.grayHoldMs = grayHoldMs;
    c.minShownKmh = minShownKmh; c.weakAccuracyM = weakAccuracyM;
    return c;
  }

  /** Obcina wartości do zakresów z ustawień (zły zapis nigdy nie psuje działania). */
  public LimitConfig clamped() {
    LimitConfig c = copy();
    c.tol = Math.max(0, Math.min(10, c.tol));
    c.glowOnKmh = Math.max(5, Math.min(100, c.glowOnKmh));
    c.showDelayMs = Math.max(0, Math.min(10_000, c.showDelayMs));
    c.keepMs = Math.max(0, Math.min(60_000, c.keepMs));
    c.lastKnownMs = Math.max(0, Math.min(600_000, c.lastKnownMs));
    c.grayHoldMs = Math.max(0, Math.min(300_000, c.grayHoldMs));
    c.minShownKmh = Math.max(0, Math.min(30, c.minShownKmh));
    c.weakAccuracyM = Math.max(5, Math.min(500, c.weakAccuracyM));
    return c;
  }
}
