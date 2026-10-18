package altergames.carlauncher.limit.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Źródło dróg dla silnika: najpierw lokalne paczki (szybko, niezawodnie, bez wysyłania dokładnej pozycji),
 * a gdy ich nie ma (nowe miejsce, poza Polską, pobieranie jeszcze trwa), zapasowo drugie źródło, np. Overpass.
 */
public final class PackSource implements RoadSource {
  /** Promień dodany do okna, żeby okno z paczek było trochę większe niż z sieci. */
  static final int EXTRA_M = 60;
  static final long WAIT_MS = 3000;

  /** Lista dróg z lokalnych paczek: silnik nie musi jej dodatkowo zapisywać do pamięci trwałej. */
  @SuppressWarnings("serial")
  public static final class LocalList extends ArrayList<Road> {
    LocalList(List<Road> l) { super(l); }
  }

  private final PackStore store;
  private final RoadSource fallback;
  private final long waitMs;

  public PackSource(PackStore store, RoadSource fallback) { this(store, fallback, WAIT_MS); }

  public PackSource(PackStore store, RoadSource fallback, long waitMs) {
    this.store = store; this.fallback = fallback; this.waitMs = waitMs;
  }

  @Override public List<Road> fetch(double lat, double lon, int radiusM) throws FetchException {
    PackStore.Lookup l = null;
    try {
      l = store.lookup(lat, lon, radiusM + EXTRA_M, waitMs);
    } catch (RuntimeException e) {
      // magazyn nigdy nie powinien rzucać, ale gdyby, jedziemy dalej na zapasowym źródle
    }
    if (l != null && l.complete) return new LocalList(l.roads);
    if (fallback != null) {
      try {
        return fallback.fetch(lat, lon, radiusM);
      } catch (FetchException e) {
        if (l != null && !l.roads.isEmpty()) return new LocalList(l.roads);   // lepsze częściowe dane niż żadne
        throw e;
      }
    }
    if (l != null && !l.roads.isEmpty()) return new LocalList(l.roads);
    throw new FetchException("brak danych o drogach");
  }
}
