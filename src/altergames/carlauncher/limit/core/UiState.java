package altergames.carlauncher.limit.core;

/** Niemutowalny opis tego, co ma pokazać kółko. Wynik LimitModel.tick, wejście Paintera. */
public final class UiState {
  /** SEARCH: szukanie GPS (zamiast znaku jest logo auta), DRIVE: normalna jazda, WEAK: słaby GPS, TUNNEL: brak GPS, ostatni znak, FREE: brak limitu. */
  public enum View { SEARCH, DRIVE, WEAK, TUNNEL, FREE }

  public final View view;
  /** Limit na znaku w km/h albo -1. */
  public final int limit;
  /** Czy limit jest szacowany (a nie jawny). */
  public final boolean estimated;
  /** Prędkość do pokazania w małym kółku, -1 gdy ukryta. */
  public final int speed;
  /** Duże przekroczenie (30 km/h i więcej, z histerezą do 27): pod znakiem jest czerwona poświata. */
  public final boolean severe;
  /** Opaska z mandatem: 0 gdy brak. */
  public final int fine;
  public final int points;
  /** Dane nieświeże: znak czarno-biały. */
  public final boolean stale;
  /** Limit z danych lokalnych bez potwierdzenia na żywo (brak internetu albo nieświeże dane): pierścień znaku jest pomarańczowy zamiast czerwonego. */
  public final boolean offline;
  /** Limit szacowany z wyłączonymi ostrzeżeniami: bez oceny koloru kółka, mandatu i poświaty (tylko znak). */
  public final boolean muted;

  public UiState(View view, int limit, boolean estimated, int speed, boolean severe,
                 int fine, int points, boolean stale) {
    this(view, limit, estimated, speed, severe, fine, points, stale, false);
  }

  public UiState(View view, int limit, boolean estimated, int speed, boolean severe,
                 int fine, int points, boolean stale, boolean offline) {
    this(view, limit, estimated, speed, severe, fine, points, stale, offline, false);
  }

  public UiState(View view, int limit, boolean estimated, int speed, boolean severe,
                 int fine, int points, boolean stale, boolean offline, boolean muted) {
    this.offline = offline;
    this.muted = muted;
    this.view = view;
    this.limit = limit;
    this.estimated = estimated;
    this.speed = speed;
    this.severe = severe;
    this.fine = fine;
    this.points = points;
    this.stale = stale;
  }

  public boolean hasBand() { return fine > 0; }

  public static UiState search() { return new UiState(View.SEARCH, -1, false, -1, false, 0, 0, false); }

  @Override public boolean equals(Object o) {
    if (!(o instanceof UiState)) return false;
    UiState u = (UiState) o;
    return view == u.view && limit == u.limit && estimated == u.estimated && speed == u.speed
        && severe == u.severe && fine == u.fine && points == u.points && stale == u.stale && offline == u.offline && muted == u.muted;
  }

  @Override public int hashCode() { return view.hashCode() * 31 + limit * 7 + speed; }
}
