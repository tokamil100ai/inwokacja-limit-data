package altergames.carlauncher.limit.core;

import java.util.List;

/** Źródło dróg wokół punktu (w aplikacji: Overpass API, w testach: atrapa). */
public interface RoadSource {
  /** Zwraca drogi w promieniu radiusM metrów. Zgłasza FetchException przy każdej porażce. */
  List<Road> fetch(double lat, double lon, int radiusM) throws FetchException;

  /** Porażka pobrania. rateLimited: serwer prosi o odczekanie (HTTP 429). */
  class FetchException extends Exception {
    public final boolean rateLimited;

    public FetchException(String message, boolean rateLimited, Throwable cause) {
      super(message, cause);
      this.rateLimited = rateLimited;
    }

    public FetchException(String message) { this(message, false, null); }
  }
}
