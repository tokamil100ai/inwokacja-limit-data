package altergames.carlauncher.limit.core;

/**
 * Najmniejszy zestaw operacji rysowania, jakich potrzebuje Painter.
 * Implementacje: Androidowa (Canvas) w aplikacji i Java2D w podglądzie na komputerze.
 * Współrzędne w jednostkach logicznych, początek w środku kółka, kolory w formacie ARGB.
 */
public interface Gfx {
  void save();
  void restore();
  void translate(float dx, float dy);
  void scale(float s);

  /** Grupa rysowana razem, ze wspólną przezroczystością i opcjonalnym filtrem czarno-białym. */
  void beginGroup(float alpha, boolean grayscale);
  void endGroup();

  void fillCircle(float cx, float cy, float r, int argb);
  /** Koło wypełnione gradientem promienistym o środku (gx, gy) i promieniu gr (od inner do outer). */
  void fillCircleRadial(float cx, float cy, float r, float gx, float gy, float gr, int innerArgb, int outerArgb);
  /** Miękka poświata na zewnątrz okręgu o promieniu r, szeroka na spread, malejąca do przezroczystości. */
  void halo(float cx, float cy, float r, float spread, int argb);
  /** Cień wewnętrzny: od przezroczystości na promieniu r*innerFrac do podanego koloru na promieniu r. */
  void innerShade(float cx, float cy, float r, float innerFrac, int argb);
  void strokeCircle(float cx, float cy, float r, float width, int argb);
  void strokeArc(float cx, float cy, float r, float startDeg, float sweepDeg, float width, int argb, boolean round);
  void strokeLine(float x1, float y1, float x2, float y2, float width, int argb, boolean round);
  void fillRoundRect(float l, float t, float r, float b, float rx, int argb);
  void strokeRoundRect(float l, float t, float r, float b, float rx, float width, int argb);
  /** Tekst pogrubiony, wyśrodkowany poziomo względem cx, linia bazowa na baseline. */
  void text(String s, float cx, float baseline, float size, int argb);
  /** Szerokość tekstu pogrubionego przy podanym rozmiarze. */
  float textWidth(String s, float size);
}
