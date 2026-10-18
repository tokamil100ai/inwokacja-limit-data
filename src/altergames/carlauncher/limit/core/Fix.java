package altergames.carlauncher.limit.core;

/** Jedna próbka GPS. Nieznane wartości to NaN. Czas w milisekundach zegara monotonicznego. */
public final class Fix {
  public final double lat;
  public final double lon;
  public final double speedKmh;
  public final double accuracyM;
  public final double bearingDeg;
  public final long timeMs;

  public Fix(double lat, double lon, double speedKmh, double accuracyM, double bearingDeg, long timeMs) {
    this.lat = lat;
    this.lon = lon;
    this.speedKmh = speedKmh;
    this.accuracyM = accuracyM;
    this.bearingDeg = bearingDeg;
    this.timeMs = timeMs;
  }

  /** Współrzędne nadają się do użycia (skończone, w zakresie, nie jest to zerowa pozycja "brak fixa"). */
  public boolean valid() {
    if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) return false;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
    return !(lat == 0 && lon == 0);
  }
}
