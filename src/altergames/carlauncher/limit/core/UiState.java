package altergames.carlauncher.limit.core;

/** Niemutowalny opis tego, co ma pokazać kółko. Wynik LimitModel.tick, wejście Paintera. */
public final class UiState {
  /** SEARCH: szukanie GPS, DRIVE: normalna jazda, WEAK: słaby GPS, TUNNEL: brak GPS, ostatni znak, FREE: brak limitu. */
  public enum View { SEARCH, DRIVE, WEAK, TUNNEL, FREE }

  public final View view;
  /** Limit na znaku w km/h albo -1. */
  public final int limit;
  /** Czy limit jest szacowany (a nie jawny). */
  public final boolean estimated;
  /** Prędkość do pokazania w małym kółku, -1 gdy ukryta. */
  public final int speed;
  /** Rumieniec przekroczenia, 0..1 (tło robi się czerwone). */
  public final float over;
  /** Wypełnienie obwodu, 0..1 (1 = 125% limitu). */
  public final float gauge;
  /** Opaska z mandatem: 0 gdy brak. */
  public final int fine;
  public final int points;
  /** Dane nieświeże: znak czarno-biały. */
  public final boolean stale;

  public UiState(View view, int limit, boolean estimated, int speed, float over, float gauge,
                 int fine, int points, boolean stale) {
    this.view = view;
    this.limit = limit;
    this.estimated = estimated;
    this.speed = speed;
    this.over = over;
    this.gauge = gauge;
    this.fine = fine;
    this.points = points;
    this.stale = stale;
  }

  public boolean hasBand() { return fine > 0; }

  public static UiState search() { return new UiState(View.SEARCH, -1, false, -1, 0f, 0f, 0, 0, false); }

  /** Klucz tego, co wymaga animacji wymiany znaku (widok i liczba na znaku). */
  public String faceKey() {
    switch (view) {
      case SEARCH: return "search";
      case FREE: return "free";
      default: return "sign:" + limit;
    }
  }

  @Override public boolean equals(Object o) {
    if (!(o instanceof UiState)) return false;
    UiState u = (UiState) o;
    return view == u.view && limit == u.limit && estimated == u.estimated && speed == u.speed
        && over == u.over && gauge == u.gauge && fine == u.fine && points == u.points && stale == u.stale;
  }

  @Override public int hashCode() { return view.hashCode() * 31 + limit * 7 + speed; }
}
