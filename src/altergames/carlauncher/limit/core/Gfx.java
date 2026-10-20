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

  /** Grupa rysowana razem, ze wspólną przezroczystością i odbarwieniem gray (0 = kolory, 1 = czarno-biało). */
  void beginGroup(float alpha, float gray);
  void endGroup();

  void fillCircle(float cx, float cy, float r, int argb);
  /** Koło o promieniu r wypełnione gradientem promienistym od środka (pos 0) do brzegu (pos 1); pos rosnące, tyle samo kolorów. */
  void fillCircleStops(float cx, float cy, float r, float[] pos, int[] argb);
  void strokeCircle(float cx, float cy, float r, float width, int argb);
  /**
   * Obwódka koła złożona z równych kresek: [dashes] kresek na obwodzie, kreska zajmuje [onFraction] okresu, a jedna kreska jest wyśrodkowana
   * na godzinie 3 (przy [dashes] podzielnym przez 4 kreski są też na 6, 9 i 12).
   */
  void strokeDashedCircle(float cx, float cy, float r, float width, int dashes, float onFraction, int argb);
  void fillRoundRect(float l, float t, float r, float b, float rx, int argb);
  void strokeRoundRect(float l, float t, float r, float b, float rx, float width, int argb);
  /** Tekst pogrubiony, wyśrodkowany poziomo względem cx, linia bazowa na baseline. */
  void text(String s, float cx, float baseline, float size, int argb);
  /** Szerokość tekstu pogrubionego przy podanym rozmiarze. */
  float textWidth(String s, float size);
}
