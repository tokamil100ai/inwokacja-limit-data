package altergames.carlauncher.limit.core;

/**
 * Rysowanie kółka. Odpowiednik prototypu webowego (agama-view-web: src/widget_limit.js), w jednostkach logicznych:
 * pole widżetu to 477, początek współrzędnych w jego środku. Znak leży bezpośrednio na tle ekranu głównego (bez własnej tarczy),
 * a pod nim, przy dużym przekroczeniu, jest miękka czerwona poświata.
 * Stan SEARCH (jeszcze nie ma GPS) rysuje wołający: to zwykłe logo auta, Painter nie rysuje wtedy nic.
 * Klasa jest czysta (bez Androida), więc te same współrzędne da się narysować i sprawdzić na komputerze.
 */
public final class Painter {
  public static final float D = 477f;
  public static final float R = D / 2f;

  /** Wszystko, czego Painter potrzebuje do jednej klatki. Wartości płynne (poświata, odbarwienie, kolor kółka) liczy {@link FrameDriver}. */
  public static final class Frame {
    public UiState state = UiState.search();
    /** Krycie czerwonej poświaty pod znakiem, 0..1. */
    public float alarm;
    /** Odbarwienie znaku, 0..1 (1 = czarno-biały, dane nieświeże). */
    public float gray;
    /** Czerwona poświata wokół znaku przy mandacie, 0..1. */
    public float glow;
    /** Kolor tła i cyfr kółka z prędkością. */
    public int badgeBg = DARK, badgeFg = WHITE;

    /** Klatka w stanie ustalonym (bez płynnych przejść): dla podglądu i testów. */
    public static Frame steady(UiState s) {
      Frame f = new Frame();
      f.state = s;
      f.alarm = alarmTarget(s);
      f.gray = grayTarget(s);
      f.glow = glowTarget(s);
      f.badgeBg = badgeBgTarget(s);
      f.badgeFg = badgeFgTarget(s);
      return f;
    }
  }

  // kolory
  static final int WHITE = 0xFFFFFFFF;
  static final int INK = 0xFF111111;
  static final int SIGN_RED = 0xFFD7141A;
  /** Pierścień znaku z danych lokalnych bez potwierdzenia na żywo (brak internetu albo nieświeże dane). */
  static final int SIGN_ORANGE = 0xFFF28A0F;
  static final int AMBER = 0xFFFFB02E;
  static final int DARK = 0xFF17171A;
  static final int GREEN = 0xFF1F9D4D;

  static final float SIGN_D = 296f;
  /** Przerywany pierścień limitu szacowanego: liczba kresek i jaka część okresu jest kreską. */
  static final int DASHES = 12;
  static final float DASH_ON = 0.65f;
  static final float BADGE_R = 58f, BADGE_CX = 104f, BADGE_CY = -104f, BADGE_BORDER = 4f;
  static final float RIBBON_TOP = 60f;
  static final float RIBBON_RHO = 14f;

  // czerwona poświata: gradient radialny "closest-side" w polu widżetu (pozycja 0..1, kolor)
  static final float[] ALARM_POS = {0f, 0.55f, 0.74f, 0.90f, 1f};
  static final int[] ALARM_COL = {
      0xF2CE1A26, 0xEDC01622, 0xD6A2101C, 0x807A0C16, 0x0046080E};
  /** Ta sama poświata w pomarańczowym odcieniu: przy znaku z danych lokalnych bez potwierdzenia na żywo (pomarańczowy pierścień). */
  static final int[] ALARM_COL_ORANGE = {
      0xF2F28A0F, 0xEDDC7A0A, 0xD6B45E08, 0x808A4605, 0x004F2E03};

  /* ---------- wartości docelowe (wspólne dla klatki ustalonej i płynnej) ---------- */

  static boolean known(UiState s) { return s.view == UiState.View.DRIVE && s.limit >= 0; }

  static float alarmTarget(UiState s) { return s.severe ? 1f : 0f; }
  static float grayTarget(UiState s) { return s.stale ? 1f : 0f; }
  static float glowTarget(UiState s) { return s.hasBand() ? 1f : 0f; }

  /** Zielone tło w limicie (od 0 do 10 km/h stopniowo z ciemnego), bursztynowe ponad limitem, ciemne gdy limit niepewny. */
  static int badgeBgTarget(UiState s) {
    if (!known(s) || s.speed < 0) return DARK;
    if (s.speed > s.limit) return AMBER;
    return mix(DARK, GREEN, Math.min(1f, s.speed / 10f));
  }

  static int badgeFgTarget(UiState s) { return known(s) && s.speed > s.limit ? INK : WHITE; }

  public void draw(Gfx g, Frame f) {
    UiState s = f.state;
    if (s.view == UiState.View.SEARCH) return;
    if (f.alarm > 0.002f) drawAlarm(g, f.alarm, s.offline);
    drawFace(g, f);
    drawRibbon(g, s);
    drawBadge(g, f);
  }

  /* ---------- tło ---------- */

  void drawAlarm(Gfx g, float a, boolean offline) {
    int[] base = offline ? ALARM_COL_ORANGE : ALARM_COL;
    int[] c = new int[base.length];
    for (int i = 0; i < c.length; i++) c[i] = scaleAlpha(base[i], a);
    g.fillCircleStops(0, 0, R, ALARM_POS, c);
  }

  /** Miękki cień koła o promieniu r: jak rozmycie gaussowskie o promieniu blur (połowa krycia na krawędzi, zanik do r + blur). */
  void softShadow(Gfx g, float cx, float cy, float r, float blur, int argb) {
    final float[] k = {0.977f, 0.84f, 0.5f, 0.16f, 0.023f, 0f};   // 1 - Phi(x / sigma), x = -blur, -blur/2, 0, blur/2, blur*0.75, blur
    float total = r + blur;
    float[] pos = {0f, (r - blur) / total, (r - blur / 2f) / total, r / total, (r + blur / 2f) / total, 1f};
    int[] col = new int[pos.length];
    for (int i = 0; i < pos.length; i++) col[i] = scaleAlpha(argb, k[i]);
    g.fillCircleStops(cx, cy, total, pos, col);
  }

  /* ---------- znak ---------- */

  void drawFace(Gfx g, Frame f) {
    UiState s = f.state;
    // cień znaku (jak drop-shadow 0 6 14 w prototypie); przy mandacie dochodzi czerwona poświata wokół znaku
    softShadow(g, 0, 6, SIGN_D / 2f, 14f, argb(0.5f, 0, 0, 0));
    if (f.glow > 0.002f) softShadow(g, 0, 0, SIGN_D / 2f, 18f, argb(0.7f * f.glow, 255, 64, 64));
    g.beginGroup(1f, f.gray);
    if (s.view == UiState.View.FREE) drawSign(g, "?", 56f, false, SIGN_RED);
    else drawSign(g, Integer.toString(s.limit), -1f, s.estimated, s.offline ? SIGN_ORANGE : SIGN_RED);
    g.endGroup();
  }

  /**
   * Znak ograniczenia (B-33): biała tarcza z czerwonym pierścieniem i czarną liczbą; fs < 0 oznacza rozmiar zależny od długości liczby.
   * Limit szacowany (nie ze znaku ani jawnego wpisu, tylko wyliczony z rodzaju drogi) ma pierścień przerywany: bez żadnego napisu widać, że nie jest pewny.
   */
  void drawSign(Gfx g, String t, float fs100, boolean dashed, int ring) {
    float u = SIGN_D / 100f;                  // jednostka znaku w jednostkach logicznych
    float fs = (fs100 > 0 ? fs100 : (t.length() >= 3 ? 36f : t.length() == 2 ? 47f : 56f)) * u;
    g.fillCircle(0, 0, 50f * u, WHITE);
    if (dashed) {
      g.strokeDashedCircle(0, 0, 41.5f * u, 11f * u, DASHES, DASH_ON, ring);
    } else {
      g.fillCircle(0, 0, 41.5f * u + 5.5f * u, ring);
      g.fillCircle(0, 0, 41.5f * u - 5.5f * u, WHITE);
    }
    g.text(t, 0, 0.35f * fs, fs, INK);
  }

  /* ---------- małe kółko z prędkością ---------- */

  void drawBadge(Gfx g, Frame f) {
    UiState s = f.state;
    boolean vis = s.view == UiState.View.DRIVE || s.view == UiState.View.WEAK || s.view == UiState.View.FREE;
    if (!vis || s.speed < 0) return;
    softShadow(g, BADGE_CX, BADGE_CY + 6f, BADGE_R, 14f, argb(0.55f, 0, 0, 0));
    g.fillCircle(BADGE_CX, BADGE_CY, BADGE_R, WHITE);
    g.fillCircle(BADGE_CX, BADGE_CY, BADGE_R - BADGE_BORDER, f.badgeBg);
    String t = Integer.toString(Math.min(999, s.speed));
    float fs = t.length() >= 3 ? 48f : 58f;
    g.text(t, BADGE_CX, BADGE_CY + 0.35f * fs, fs, f.badgeFg);
  }

  /* ---------- opaska z mandatem ---------- */

  /** Wynik układu opaski: rozmiar czcionki kwoty, prostokąt (względem środka kółka) i szerokości tekstów. */
  public static final class Ribbon {
    public float fs, left, top, right, bottom, wAmt, wPts;
    public String amt, pts;
  }

  /**
   * Układ opaski: największa czcionka kwoty, przy której opaska mieści się w kółku z zapasem
   * (liczone po zaokrąglonych rogach) i nie przekracza 336 jednostek szerokości.
   */
  public static Ribbon layoutRibbon(Gfx m, int fine, int points) {
    Ribbon r = new Ribbon();
    r.amt = Penalties.formatPln(fine);
    r.pts = points + " pkt";
    float maxR = R - 11f;
    float fs = 66f;
    while (true) {
      float wAmt = m.textWidth(r.amt, fs), wPts = m.textWidth(r.pts, fs * 0.7f);
      float boxW = Math.max(wAmt, wPts) + 56f + 6f;
      float boxH = 24f + 1.7f * fs;
      float bottom = RIBBON_TOP + boxH;
      float x = Math.max(0f, boxW / 2f - RIBBON_RHO), y = bottom - RIBBON_RHO;
      boolean fits = (float) Math.hypot(x, y) + RIBBON_RHO <= maxR && boxW <= 336f;
      if (fits || fs <= 26f) {
        r.fs = fs; r.wAmt = wAmt; r.wPts = wPts;
        r.left = -boxW / 2f; r.right = boxW / 2f; r.top = RIBBON_TOP; r.bottom = bottom;
        return r;
      }
      fs -= 1f;
    }
  }

  void drawRibbon(Gfx g, UiState s) {
    if (!s.hasBand()) return;
    Ribbon r = layoutRibbon(g, s.fine, s.points);
    // cień (kilka warstw zamiast rozmycia)
    for (int i = 4; i >= 1; i--) {
      g.fillRoundRect(r.left - i * 2, r.top + 8 - i * 2, r.right + i * 2, r.bottom + 8 + i * 2, RIBBON_RHO + i * 2, argb(0.12f, 0, 0, 0));
    }
    g.fillRoundRect(r.left, r.top, r.right, r.bottom, RIBBON_RHO, AMBER);
    g.strokeRoundRect(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f, RIBBON_RHO - 1.5f, 3f, INK);
    float line1 = r.top + 3f + 8f;
    g.text(r.amt, 0, line1 + 0.84f * r.fs, r.fs, INK);
    g.text(r.pts, 0, line1 + r.fs + 3f + 0.84f * (r.fs * 0.7f), r.fs * 0.7f, INK);
  }

  /* ---------- narzędzia ---------- */

  public static int argb(float a, int r, int gr, int b) {
    int ai = Math.max(0, Math.min(255, Math.round(a * 255f)));
    return (ai << 24) | (r << 16) | (gr << 8) | b;
  }

  /** Ten sam kolor z krytym pomnożonym przez f (0..1). */
  static int scaleAlpha(int argb, float f) {
    int a = Math.round(((argb >>> 24) & 255) * Math.max(0f, Math.min(1f, f)));
    return (a << 24) | (argb & 0x00FFFFFF);
  }

  static int mix(int c0, int c1, float t) {
    t = clamp01(t);
    int a = Math.round(((c0 >>> 24) & 255) * (1 - t) + ((c1 >>> 24) & 255) * t);
    int r = Math.round(((c0 >> 16) & 255) * (1 - t) + ((c1 >> 16) & 255) * t);
    int g = Math.round(((c0 >> 8) & 255) * (1 - t) + ((c1 >> 8) & 255) * t);
    int b = Math.round((c0 & 255) * (1 - t) + (c1 & 255) * t);
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  static float clamp01(float v) { return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v)); }
}
