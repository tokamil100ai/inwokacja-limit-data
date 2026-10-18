package altergames.carlauncher.limit.core;

/**
 * Rysowanie kółka. Odpowiednik prototypu webowego (app.js i style.css), w jednostkach logicznych:
 * średnica kółka to 477, początek współrzędnych w jego środku.
 * Klasa jest czysta (bez Androida), więc te same współrzędne da się narysować i sprawdzić na komputerze.
 */
public final class Painter {
  public static final float D = 477f;
  public static final float R = D / 2f;

  /** Co jest narysowane na znaku: znak z liczbą, szary znak "?" albo ikona szukania GPS. */
  public static final class Face {
    public enum Kind { SIGN, UNKNOWN, SEARCH }
    public final Kind kind;
    public final int limit;
    public final boolean stale;

    public Face(Kind kind, int limit, boolean stale) { this.kind = kind; this.limit = limit; this.stale = stale; }

    public static Face of(UiState s) {
      switch (s.view) {
        case SEARCH: return new Face(Kind.SEARCH, -1, false);
        case FREE: return new Face(Kind.UNKNOWN, -1, false);
        default: return new Face(Kind.SIGN, s.limit, s.stale);
      }
    }
  }

  /** Wszystko, czego Painter potrzebuje do jednej klatki. */
  public static final class Frame {
    public UiState state = UiState.search();
    public float overShown;          // wygładzony rumieniec przekroczenia 0..1
    public Face face;                // aktualny wygląd środka
    public Face prevFace;            // poprzedni (znika), może być null
    public long faceAgeMs = Long.MAX_VALUE;   // czas od zmiany wyglądu
    public boolean ping;             // czy pokazać pierścień "ping" po zmianie znaku
    public long ribbonAgeMs = Long.MAX_VALUE; // czas od pojawienia się opaski
    public long timeMs;              // do pulsowania ikony szukania
  }

  // kolory
  static final int WHITE = 0xFFFFFFFF;
  static final int INK = 0xFF111111;
  static final int SIGN_RED = 0xFFD7141A;
  static final int AMBER = 0xFFFFB02E;
  static final int GAUGE_AMBER = 0xFFFFB42A;
  static final int GLYPH = 0xFFC9C9CF;
  static final int DARK = 0xFF17171A;
  static final int UNKNOWN_RING = 0xFF8A8A92;
  static final int UNKNOWN_INK = 0xFF4A4A51;

  // kształt obwodu: łuk 240 stopni z przerwą na dole, znacznik limitu na 80%
  static final float GAUGE_R = 47.8f * D / 100f;
  static final float GAUGE_W = 1.7f * D / 100f;
  static final float GAUGE_START = 150f, GAUGE_SWEEP = 240f, GAUGE_F0 = 0.8f;

  static final float SIGN_D = 296f;
  static final float RIBBON_TOP = 60f;
  static final float RIBBON_RHO = 14f;

  public void draw(Gfx g, Frame f) {
    UiState s = f.state;
    drawDisc(g, f.overShown);
    drawGauge(g, s);

    // cień znaku i poświata przy przekroczeniu
    if (s.view != UiState.View.SEARCH) g.halo(0, 6, SIGN_D / 2f, 14f, argb(0.5f, 0, 0, 0));
    if (s.hasBand()) g.halo(0, 0, SIGN_D / 2f, 18f, argb(0.7f, 255, 64, 64));

    drawFaces(g, f);
    drawBadge(g, s);
    drawRibbon(g, s, f.ribbonAgeMs);
    drawBottom(g, s);
  }

  /* ---------- tło ---------- */

  void drawDisc(Gfx g, float over) {
    float o = clamp01(over);
    int inner = mix(0xFF232326, 0xFF9B111A, o);
    int outer = mix(0xFF0C0C0E, 0xFF35060A, o);
    g.fillCircleRadial(0, 0, R, 0, -0.14f * D, 0.812f * D, inner, outer);
    g.innerShade(0, 0, R, (R - 38f) / R, argb(0.6f, 0, 0, 0));
  }

  void drawGauge(Gfx g, UiState s) {
    boolean live = s.view == UiState.View.DRIVE || s.view == UiState.View.WEAK;
    g.beginGroup(s.stale ? 0.55f : 1f, s.stale);
    g.strokeArc(0, 0, GAUGE_R, GAUGE_START, GAUGE_SWEEP, GAUGE_W, argb(0.16f, 255, 255, 255), true);
    if (live) {
      float gp = clamp01(s.gauge);
      if (gp > 0.004f) g.strokeArc(0, 0, GAUGE_R, GAUGE_START, Math.min(gp, GAUGE_F0) * GAUGE_SWEEP, GAUGE_W, WHITE, true);
      float over = (gp - GAUGE_F0) * GAUGE_SWEEP;
      if (over > 0.7f) g.strokeArc(0, 0, GAUGE_R, GAUGE_START + GAUGE_F0 * GAUGE_SWEEP, over, GAUGE_W, GAUGE_AMBER, true);
      double a = Math.toRadians(GAUGE_START + GAUGE_F0 * GAUGE_SWEEP);
      float r1 = 43.4f * D / 100f, r2 = 49.6f * D / 100f;
      g.strokeLine((float) (r1 * Math.cos(a)), (float) (r1 * Math.sin(a)),
          (float) (r2 * Math.cos(a)), (float) (r2 * Math.sin(a)), 1.5f * D / 100f, WHITE, true);
    }
    g.endGroup();
  }

  /* ---------- środek ---------- */

  void drawFaces(Gfx g, Frame f) {
    long age = f.faceAgeMs;
    if (f.prevFace != null && age < 220) {
      float t = age / 220f, p = t * t;                        // wyjście: przyspieszenie
      drawFace(g, f.prevFace, 1f + 0.12f * p, 1f - p, f.timeMs);
    }
    float scale = 1f, alpha = 1f;
    if (age < 380) {
      float p = easeOutBack(age / 380f);
      scale = 0.8f + 0.2f * p;
      alpha = clamp01(p);
    }
    if (f.face != null) drawFace(g, f.face, scale, alpha, f.timeMs);
    if (f.ping && age < 700) {
      float t = age / 700f, e = 1f - (1f - t) * (1f - t);   // ease-out
      float sc = 1f + 0.38f * e;
      g.strokeCircle(0, 0, (SIGN_D / 2f + 2f) * sc, 4f * sc, argb(0.85f * (1f - e), 255, 255, 255));
    }
  }

  void drawFace(Gfx g, Face face, float scale, float alpha, long timeMs) {
    if (alpha <= 0.002f) return;
    g.save();
    g.scale(scale);
    switch (face.kind) {
      case SIGN:
        g.beginGroup(alpha, face.stale);
        drawSign(g, face.limit);
        g.endGroup();
        break;
      case UNKNOWN:
        g.beginGroup(alpha, false);
        drawUnknown(g);
        g.endGroup();
        break;
      default: {
        // ikona szukania: wolne, delikatne pulsowanie (3,4 s) między 0,75 a 0,35
        double ph = (timeMs % 3400L) / 3400.0;
        float a = (float) (0.55 + 0.20 * Math.cos(2 * Math.PI * ph));
        glyph(g, 'S', 0, 0, 109f / 84f, argb(a * alpha, 0xC9, 0xC9, 0xCF));
      }
    }
    g.restore();
  }

  void drawSign(Gfx g, int limit) {
    String t = Integer.toString(limit);
    float u = SIGN_D / 100f;                  // jednostka znaku w jednostkach logicznych
    float fs = (t.length() >= 3 ? 36f : t.length() == 2 ? 47f : 56f) * u;
    g.fillCircle(0, 0, 50f * u, WHITE);
    g.fillCircle(0, 0, 41.5f * u + 5.5f * u, SIGN_RED);
    g.fillCircle(0, 0, 41.5f * u - 5.5f * u, WHITE);
    g.text(t, 0, 0.35f * fs, fs, INK);
  }

  void drawUnknown(Gfx g) {
    // jak znak drogowy z limitem (ta sama tarcza i grubość pierścienia), ale pierścień szary, a w środku "?":
    // od razu widać, że to nie limit, tylko brak informacji
    float u = SIGN_D / 100f;
    g.fillCircle(0, 0, 50f * u, WHITE);
    g.fillCircle(0, 0, 41.5f * u + 5.5f * u, UNKNOWN_RING);
    g.fillCircle(0, 0, 41.5f * u - 5.5f * u, WHITE);
    float fs = 56f * u;
    g.text("?", 0, 0.35f * fs, fs, UNKNOWN_INK);
  }

  /** Ikona lokalizacji w układzie 100x100 (ramka 8..92), kind: 'S' szukanie, 'W' słaby, 'L' utracony (przekreślony). */
  void glyph(Gfx g, char kind, float cx, float cy, float s, int argb) {
    g.save();
    g.translate(cx, cy);
    g.scale(s);
    g.translate(-50f, -50f);
    float w = 6f;
    if (kind == 'W') {
      // przerywany okrąg: 9 kreski, 7,5 przerwy (jednostki ikony) na promieniu 24
      double onDeg = Math.toDegrees(9.0 / 24.0), offDeg = Math.toDegrees(7.5 / 24.0);
      for (double a = 0; a < 360 - 1; a += onDeg + offDeg) {
        g.strokeArc(50, 50, 24, (float) a, (float) Math.min(onDeg, 360 - a), w, argb, true);
      }
    } else {
      g.strokeCircle(50, 50, 24, w, argb);
    }
    g.strokeLine(50, 8, 50, 22, w, argb, true);
    g.strokeLine(50, 78, 50, 92, w, argb, true);
    g.strokeLine(8, 50, 22, 50, w, argb, true);
    g.strokeLine(78, 50, 92, 50, w, argb, true);
    g.fillCircle(50, 50, 7f, argb);
    if (kind == 'L') {
      g.strokeLine(17, 17, 83, 83, 14f, DARK, true);
      g.strokeLine(17, 17, 83, 83, w, argb, true);
    }
    g.restore();
  }

  /* ---------- małe kółko z prędkością ---------- */

  void drawBadge(Gfx g, UiState s) {
    boolean vis = s.view == UiState.View.DRIVE || s.view == UiState.View.WEAK || s.view == UiState.View.FREE;
    if (!vis || s.speed < 0) return;
    float cx = 104f, cy = -104f, r = 44f;
    int col = s.hasBand() ? AMBER : WHITE;
    g.halo(cx, cy + 6f, r, 14f, argb(0.55f, 0, 0, 0));
    g.fillCircle(cx, cy, r, DARK);
    g.strokeCircle(cx, cy, r - 2f, 4f, col);
    String t = Integer.toString(Math.min(999, s.speed));
    float fs = t.length() >= 3 ? 36f : 44f;
    g.text(t, cx, cy + 0.35f * fs, fs, col);
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
    r.pts = points + " pkt";
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

  void drawRibbon(Gfx g, UiState s, long ageMs) {
    if (!s.hasBand()) return;
    Ribbon r = layoutRibbon(g, s.fine, s.points);
    float a = 1f, dy = 0f, sc = 1f;
    if (ageMs < 220) {
      float t = ageMs / 220f, e = 1f - (1f - t) * (1f - t);
      a = e; dy = -10f * (1f - e); sc = 0.92f + 0.08f * e;
    }
    g.save();
    g.translate(0, RIBBON_TOP + (r.bottom - RIBBON_TOP) / 2f + dy);
    g.scale(sc);
    g.translate(0, -(RIBBON_TOP + (r.bottom - RIBBON_TOP) / 2f));
    g.beginGroup(a, false);
    // cień (kilka warstw zamiast rozmycia)
    for (int i = 4; i >= 1; i--) {
      g.fillRoundRect(r.left - i, r.top + 8 - i, r.right + i, r.bottom + 8 + i, RIBBON_RHO + i, argb(0.12f, 0, 0, 0));
    }
    g.fillRoundRect(r.left, r.top, r.right, r.bottom, RIBBON_RHO, AMBER);
    g.strokeRoundRect(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f, RIBBON_RHO - 1.5f, 3f, INK);
    float line1 = r.top + 3f + 8f;
    g.text(r.amt, 0, line1 + 0.84f * r.fs, r.fs, INK);
    g.text(r.pts, 0, line1 + r.fs + 3f + 0.84f * (r.fs * 0.7f), r.fs * 0.7f, INK);
    g.endGroup();
    g.restore();
  }

  /* ---------- dół: ikony stanu GPS albo podpis limitu szacowanego ---------- */

  void drawBottom(Gfx g, UiState s) {
    if (s.hasBand()) return;
    if (s.view == UiState.View.WEAK) glyph(g, 'W', 0, 180f, 45f / 84f, argb(0.85f, 0xC9, 0xC9, 0xCF));
    else if (s.view == UiState.View.TUNNEL) glyph(g, 'L', 0, 180f, 45f / 84f, argb(0.85f, 0xC9, 0xC9, 0xCF));
    else if (s.view == UiState.View.DRIVE && s.estimated) {
      g.text("Limit szacowany", 0, 180f + 0.35f * 30f, 30f, argb(0.94f, 255, 255, 255));
    }
  }

  /* ---------- narzędzia ---------- */

  public static int argb(float a, int r, int gr, int b) {
    int ai = Math.max(0, Math.min(255, Math.round(a * 255f)));
    return (ai << 24) | (r << 16) | (gr << 8) | b;
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

  /** Przybliżenie krzywej cubic-bezier(.2,.9,.25,1.15) z lekkim przeregulowaniem. */
  static float easeOutBack(float t) {
    t = clamp01(t);
    float c1 = 1.0f, c3 = c1 + 1f;
    float p = t - 1f;
    return 1f + c3 * p * p * p + c1 * p * p;
  }
}
