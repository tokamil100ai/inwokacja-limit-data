package altergames.carlauncher.limit.core;

/**
 * Siatka podziału bazy dróg. Kafelek to 0,02 x 0,03 stopnia (około 2,2 x 2,0 km), paczka to 5 x 5 kafelków
 * (0,10 x 0,15 stopnia, około 11 x 10 km). Aplikacja pobiera i trzyma całe paczki, ale tylko te, w których jest
 * albo za chwilę będzie. Te same stałe używa program budujący bazę i aplikacja, więc muszą się zgadzać.
 */
public final class PackGrid {
  public static final int TILE_LAT_MICRO = 20_000;
  public static final int TILE_LON_MICRO = 30_000;
  public static final int PACK_TILES = 5;

  private PackGrid() { }

  public static int tileX(double lon) { return (int) Math.floorDiv((long) Math.floor(lon * 1e6), (long) TILE_LON_MICRO); }
  public static int tileY(double lat) { return (int) Math.floorDiv((long) Math.floor(lat * 1e6), (long) TILE_LAT_MICRO); }
  public static int packOfTile(int t) { return Math.floorDiv(t, PACK_TILES); }
  public static int packX(double lon) { return packOfTile(tileX(lon)); }
  public static int packY(double lat) { return packOfTile(tileY(lat)); }

  public static String name(int px, int py) { return "p_" + px + "_" + py + ".rdb"; }

  /** Klucz do map: te same reguły co w RoadDb.key. */
  public static long key(int px, int py) { return RoadDb.key(px, py); }

  /** Środek paczki, do odległości przy usuwaniu najdawniej używanych. */
  public static double centerLat(int py) { return ((py * (double) PACK_TILES + PACK_TILES / 2.0) * TILE_LAT_MICRO) / 1e6; }
  public static double centerLon(int px) { return ((px * (double) PACK_TILES + PACK_TILES / 2.0) * TILE_LON_MICRO) / 1e6; }
}
