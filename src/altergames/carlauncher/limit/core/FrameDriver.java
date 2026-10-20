package altergames.carlauncher.limit.core;

/**
 * Zamienia kolejne stany (UiState) na klatki dla Paintera i wygładza płynne przejścia z prototypu: czerwoną poświatę pod znakiem (0,6 s),
 * odbarwienie nieświeżego znaku i poświatę przy mandacie (0,4 s), kolor kółka z prędkością (0,25 s). Sam znak i opaska zmieniają się
 * natychmiast, bez animacji. Czysta Java, więc da się ją testować bez Androida.
 */
public final class FrameDriver {
  /** Stałe czasowe wygładzania wykładniczego: po trzykrotności zostaje ok. 5% różnicy, co odpowiada czasom przejść w prototypie. */
  static final float ALARM_TAU_MS = 200f, FADE_TAU_MS = 130f, BADGE_TAU_MS = 80f;

  private final Painter.Frame frame = new Painter.Frame();
  private long lastMs = -1;
  private boolean animating;

  public FrameDriver() { frame.state = UiState.search(); }

  /** Następna klatka dla stanu s w chwili nowMs (ms, dowolny stały punkt odniesienia). Nie zgłasza wyjątków. */
  public Painter.Frame advance(UiState s, long nowMs) { return advance(s, nowMs, null); }

  /** To samo z wyglądem z ustawień (kolory i opcje kółka, szarość); null = domyślny. */
  public Painter.Frame advance(UiState s, long nowMs, Painter.Style style) {
    if (s == null) s = UiState.search();
    Painter.Style st = style == null ? new Painter.Style() : style;
    float dt = lastMs < 0 ? -1f : Math.max(0f, Math.min(250f, nowMs - lastMs));
    lastMs = nowMs;
    Painter.Frame f = frame;

    float alarmT = Painter.alarmTarget(s), grayT = Painter.grayTarget(s, st), glowT = Painter.glowTarget(s);
    int bgT = Painter.badgeBgTarget(s, st), fgT = Painter.badgeFgTarget(s, st);
    if (dt < 0) {                                                       // pierwsza klatka: od razu stan docelowy
      f.alarm = alarmT; f.gray = grayT; f.glow = glowT; f.badgeBg = bgT; f.badgeFg = fgT;
    } else {
      f.alarm = approach(f.alarm, alarmT, dt, ALARM_TAU_MS);
      f.gray = approach(f.gray, grayT, dt, FADE_TAU_MS);
      f.glow = approach(f.glow, glowT, dt, FADE_TAU_MS);
      float kb = 1f - (float) Math.exp(-dt / BADGE_TAU_MS);
      f.badgeBg = near(f.badgeBg, bgT) ? bgT : Painter.mix(f.badgeBg, bgT, kb);
      f.badgeFg = near(f.badgeFg, fgT) ? fgT : Painter.mix(f.badgeFg, fgT, kb);
    }
    f.state = s;
    f.style = st;
    animating = f.alarm != alarmT || f.gray != grayT || f.glow != glowT || f.badgeBg != bgT || f.badgeFg != fgT;
    return f;
  }

  /** Czy trwa przejście i trzeba rysować często (np. 30 razy na sekundę); w spokoju wystarczy rzadko. */
  public boolean isAnimating() { return animating; }

  private static float approach(float cur, float target, float dt, float tau) {
    float k = 1f - (float) Math.exp(-dt / tau);
    float v = cur + (target - cur) * k;
    return Math.abs(target - v) < 0.002f ? target : v;
  }

  /** Kolory różniące się o niewiele na kanał uznajemy za równe, żeby przejście się kończyło. */
  private static boolean near(int a, int b) {
    for (int sh = 0; sh <= 24; sh += 8) if (Math.abs(((a >> sh) & 255) - ((b >> sh) & 255)) > 1) return false;
    return true;
  }
}
