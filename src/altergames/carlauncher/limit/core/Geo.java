package altergames.carlauncher.limit.core;

/** Drobna geometria na płaszczyźnie lokalnej (metry), wystarczająca na odległości rzędu kilometrów. Czysta Java. */
public final class Geo {
  private Geo() { }


  public static double dist(double[] a, double[] b) { return dist(a[0], a[1], b[0], b[1]); }

  public static double dist(double la1, double lo1, double la2, double lo2) {
    double dy = (la2 - la1) * 110540.0, dx = (lo2 - lo1) * 111320.0 * Math.cos(Math.toRadians((la1 + la2) / 2));
    return Math.sqrt(dx * dx + dy * dy);
  }

  /** Azymut z a do b w stopniach 0..360. */
  public static double bearing(double[] a, double[] b) {
    double dy = (b[0] - a[0]) * 110540.0, dx = (b[1] - a[1]) * 111320.0 * Math.cos(Math.toRadians((a[0] + b[0]) / 2));
    double deg = Math.toDegrees(Math.atan2(dx, dy));
    return deg < 0 ? deg + 360 : deg;
  }

  /** Różnica kątów 0..180. */
  public static double angDiff(double a, double b) {
    double d = Math.abs(((a - b) % 360 + 360) % 360);
    return d > 180 ? 360 - d : d;
  }

  /** Odległość punktu od odcinka a-b w metrach. */
  public static double segDist(double lat, double lon, double[] a, double[] b) {
    double cos = Math.cos(Math.toRadians(lat));
    double px = (lon - a[1]) * 111320.0 * cos, py = (lat - a[0]) * 110540.0;
    double bx = (b[1] - a[1]) * 111320.0 * cos, by = (b[0] - a[0]) * 110540.0;
    double len2 = bx * bx + by * by;
    double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
    double dx = px - t * bx, dy = py - t * by;
    return Math.sqrt(dx * dx + dy * dy);
  }
}
