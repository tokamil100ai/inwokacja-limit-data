package altergames.carlauncher.limit.core;

/**
 * Zamienia kolejne stany (UiState) na klatki animacji dla Paintera: wygładza rumieniec przekroczenia, pilnuje
 * wymiany znaku z efektem "ping" i wiek opaski. Czysta Java, więc da się ją testować bez Androida.
 */
public final class FrameDriver {
  /** Stała czasowa wygładzania rumieńca: po 0,6 s zostaje kilka procent różnicy (jak w prototypie, ease 0,6 s). */
  static final float OVER_TAU_MS = 200f;
  static final long FACE_ANIM_MS = 700;
  static final long RIBBON_ANIM_MS = 260;

  private final Painter.Frame frame = new Painter.Frame();
  private String faceKey;
  private Painter.Face face, prev;
  private long faceStart;
  private boolean ping;
  private float overShown;
  private long lastMs = -1;
  private boolean bandActive;
  private long bandStart;
  private boolean animating = true;

  public FrameDriver() { frame.state = UiState.search(); }

  /** Następna klatka dla stanu s w chwili nowMs (ms, dowolny stały punkt odniesienia). Nie zgłasza wyjątków. */
  public Painter.Frame advance(UiState s, long nowMs) {
    if (s == null) s = UiState.search();
    float dt = lastMs < 0 ? 0f : Math.max(0f, Math.min(250f, nowMs - lastMs));
    lastMs = nowMs;

    // rumieniec: wygładzanie wykładnicze do wartości docelowej
    float target = Math.max(0f, Math.min(1f, s.over));
    float k = 1f - (float) Math.exp(-dt / OVER_TAU_MS);
    overShown += (target - overShown) * k;
    if (Math.abs(target - overShown) < 0.002f) overShown = target;

    // znak: zmiana liczby/widoku to wymiana z animacją, zmiana samej szarości to podmiana w miejscu
    String key = s.faceKey();
    Painter.Face nf = Painter.Face.of(s);
    if (faceKey == null) {
      face = nf; faceKey = key; faceStart = Long.MIN_VALUE / 4; ping = false; prev = null;
    } else if (!key.equals(faceKey)) {
      prev = face;
      face = nf;
      faceKey = key;
      faceStart = nowMs;
      ping = s.view != UiState.View.SEARCH;
    } else {
      face = nf;
    }
    long faceAge = nowMs - faceStart;
    if (faceAge >= FACE_ANIM_MS) { prev = null; ping = false; }

    // opaska: wiek liczony od chwili pojawienia się
    boolean band = s.hasBand();
    if (band && !bandActive) bandStart = nowMs;
    bandActive = band;
    long ribbonAge = band ? nowMs - bandStart : Long.MAX_VALUE;

    frame.state = s;
    frame.overShown = overShown;
    frame.face = face;
    frame.prevFace = prev;
    frame.faceAgeMs = faceAge >= FACE_ANIM_MS ? Long.MAX_VALUE : faceAge;
    frame.ping = ping;
    frame.ribbonAgeMs = ribbonAge >= RIBBON_ANIM_MS ? Long.MAX_VALUE : ribbonAge;
    frame.timeMs = nowMs;

    animating = s.view == UiState.View.SEARCH || faceAge < FACE_ANIM_MS || ribbonAge < RIBBON_ANIM_MS || overShown != target;
    return frame;
  }

  /** Czy trwa animacja i trzeba rysować często (np. 30 razy na sekundę); w spokoju wystarczy rzadko. */
  public boolean isAnimating() { return animating; }
}
