package altergames.carlauncher.limit.core;

/**
 * Urządzenie kontrolujące ruch: fotoradar, początek albo koniec odcinkowego pomiaru prędkości, kamera na czerwonym świetle.
 * Dane są w paczkach razem z drogami (format v2), więc aplikacja zna je offline. Obiekt niemutowalny.
 * Odcinkowy pomiar to dwa obiekty (początek i koniec) o wspólnym [pair]; długość odcinka jest przy początku.
 */
public final class Camera {
  public static final int SPEED = 0, SECTION_START = 1, SECTION_END = 2, RED_LIGHT = 3, RAIL_LIGHT = 4;
  /** Skąd wiemy o urządzeniu (maska bitów): wpis w OpenStreetMap i/lub w wykazie ITD (GITD). */
  public static final int SRC_OSM = 1, SRC_ITD = 2;
  public static final int DIR_ANY = -1;
  /** Dłuższych odcinków nie zapisujemy: błąd w danych nie może zepsuć kafelka (czytnik odrzuca nierealne wartości). */
  public static final int MAX_SECTION_M = 100_000;

  /** Stabilny numer: węzeł OSM albo skrót kodu ITD. */
  public final long id;
  public final int kind;
  public final double lat, lon;
  /** Kierunek jazdy, którego dotyczy kontrola, w stopniach 0..359 (zaokrąglony do 2); [DIR_ANY], gdy oba albo nieznany. */
  public final int dir;
  /** Limit w km/h, który egzekwuje urządzenie; 0, gdy źródło go nie podaje. */
  public final int limit;
  /** Długość odcinka w metrach (tylko przy początku odcinka), 0 gdy nieznana. */
  public final int sectionLen;
  public final int sources;
  /** Wspólny numer początku i końca odcinkowego pomiaru; 0, gdy nie dotyczy. */
  public final long pair;

  public Camera(long id, int kind, double lat, double lon, int dir, int limit, int sectionLen, int sources, long pair) {
    this.id = id;
    this.kind = kind;
    this.lat = lat;
    this.lon = lon;
    this.dir = dir < 0 ? DIR_ANY : ((dir % 360) / 2) * 2;
    this.limit = Maxspeed.validLimit(limit) ? limit : 0;
    this.sectionLen = Math.max(0, Math.min(sectionLen, MAX_SECTION_M));
    this.sources = sources;
    this.pair = pair;
  }

  public boolean hasDirection() { return dir != DIR_ANY; }

  public Camera withKindPairLen(int newKind, long newPair, int len) {
    return new Camera(id, newKind, lat, lon, dir, limit, len, sources, newPair);
  }

  public Camera withDir(int newDir) { return new Camera(id, kind, lat, lon, newDir, limit, sectionLen, sources, pair); }

  public Camera withSources(int s) { return new Camera(id, kind, lat, lon, dir, limit, sectionLen, s, pair); }

  @Override public String toString() {
    String[] k = {"radar", "odc.początek", "odc.koniec", "czerwone", "kolej"};
    return k[kind] + "@" + String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon) + (dir >= 0 ? " " + dir + "°" : "")
        + (limit > 0 ? " " + limit : "") + (sectionLen > 0 ? " " + sectionLen + "m" : "") + " src" + sources;
  }
}
