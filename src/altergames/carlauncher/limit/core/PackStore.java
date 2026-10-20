package altergames.carlauncher.limit.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.zip.CRC32;

/**
 * Lokalny magazyn paczek dróg pobieranych w locie. Aplikacja nie trzyma całych województw, tylko małe paczki
 * (około 11 x 10 km) z miejsc, w których jest albo za chwilę będzie. Magazyn:
 *  - zna spis paczek z manifestu (odświeżany co kilka godzin),
 *  - ma wbudowane dane startowe (PackSeed): potrzebną paczkę kopiuje z aplikacji, bez internetu i bez czekania,
 *  - gdy sieć jest dobra, po cichu odświeża w tle paczki, które na serwerze się zmieniły (najbliższe najpierw, w dziennym limicie),
 *  - pobiera brakującą paczkę z bieżącego miejsca oraz sąsiednie, gdy pozycja jest blisko krawędzi,
 *  - sprawdza rozmiar i sumę kontrolną przed instalacją i wymienia plik atomowo,
 *  - wymienia paczki, które na serwerze się zmieniły, a nie rusza niezmienionych,
 *  - pilnuje miejsca na dysku (najdawniej używane wylatują, bliskie okolice nigdy),
 *  - po porażce odczekuje coraz dłużej, nie zalewa sieci i nigdy nie zgłasza wyjątków na zewnątrz.
 * Praca sieciowa idzie na wątku roboczym (Executor), odczyt lokalny jest szybki.
 */
public final class PackStore {
  /** Jak często (w czasie pracy) sprawdzamy, czy na serwerze są nowsze dane; dane powstają raz na dobę, więc kilka godzin wystarcza. */
  public static final long MANIFEST_MAX_AGE_MS = 4L * 3600 * 1000;
  public static final long DEFAULT_QUOTA = 160L * 1024 * 1024;
  static final double EDGE_M = 2500;
  static final int MAX_HANDLES = 4;
  static final long MIN_FREE_BYTES = 40L * 1024 * 1024;
  static final long DEFAULT_PROTECT_RADIUS_M = 40_000;
  static final long MAX_BACKOFF_MS = 30L * 60 * 1000;
  private static final Charset UTF8 = Charset.forName("UTF-8");

  public static final class Lookup {
    public final List<Road> roads;
    /** Wszystkie paczki okna są lokalnie (dane kompletne). */
    public final boolean complete;
    public final int have, missing, absent, unknown;
    Lookup(List<Road> roads, boolean complete, int have, int missing, int absent, int unknown) {
      this.roads = roads; this.complete = complete; this.have = have; this.missing = missing; this.absent = absent; this.unknown = unknown;
    }
  }

  private static final class LocalPack {
    long size, crc, usedWall;
    LocalPack(long size, long crc, long usedWall) { this.size = size; this.crc = crc; this.usedWall = usedWall; }
  }

  private static final class Retry {
    long at; int failures;
  }

  private final File dir, packsDir;
  private final String baseUrl;
  private final PackClient client;
  private final LimitEngine.Clock clock;
  private final Executor worker;
  private final long quota;
  private final PackSeed seed;
  private final PackPolicy policy;
  private final PackSigning.Verifier verifier;
  private Manifest seedManifest;
  private boolean manifestIsSeed;
  private final Set<Long> seedBad = new HashSet<Long>();
  private long protectRadiusM = DEFAULT_PROTECT_RADIUS_M;

  private boolean disabled;
  private Manifest manifest;
  private long manifestChecked;
  private boolean manifestWanted, draining, closed;
  private long manifestRetryAt;
  private int manifestFailures;
  private final Map<Long, LocalPack> local = new HashMap<Long, LocalPack>();
  private final Map<Long, Retry> retry = new HashMap<Long, Retry>();
  private final Set<Long> wanted = new LinkedHashSet<Long>();
  private final Set<Long> urgent = new HashSet<Long>();
  private final Map<Long, Integer> corruptCount = new HashMap<Long, Integer>();
  private final LinkedHashMap<Long, RoadDb> handles = new LinkedHashMap<Long, RoadDb>(8, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long, RoadDb> e) {
      if (size() > MAX_HANDLES) { e.getValue().close(); return true; }
      return false;
    }
  };
  private double curLat, curLon, lastLat, lastLon;
  private boolean haveCur, haveLast;
  private long budgetDay, budgetBytes, bulkCheckedAt;
  private int downloads, downloadFailures, manifestFetches, seedInstalls, bulkDownloads;
  private String lastError = "";

  public PackStore(File dir, String baseUrl, PackClient client, LimitEngine.Clock clock, Executor worker, long quotaBytes) {
    this(dir, baseUrl, client, clock, worker, quotaBytes, null, PackPolicy.NONE);
  }

  public PackStore(File dir, String baseUrl, PackClient client, LimitEngine.Clock clock, Executor worker, long quotaBytes,
                   PackSeed seed, PackPolicy policy) {
    this(dir, baseUrl, client, clock, worker, quotaBytes, seed, policy, null);
  }

  /** verifier: sprawdzanie podpisu spisu (manifest.sig); null = bez podpisu (testy). Spis bez poprawnego podpisu jest odrzucany. */
  public PackStore(File dir, String baseUrl, PackClient client, LimitEngine.Clock clock, Executor worker, long quotaBytes,
                   PackSeed seed, PackPolicy policy, PackSigning.Verifier verifier) {
    this.verifier = verifier;
    this.seed = seed;
    this.policy = policy == null ? PackPolicy.NONE : policy;
    this.dir = dir;
    this.packsDir = new File(dir, "packs");
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    this.client = client;
    this.clock = clock;
    this.worker = worker;
    this.quota = quotaBytes;
    try {
      if (!packsDir.isDirectory() && !packsDir.mkdirs()) { disabled = true; lastError = "nie można utworzyć katalogu"; return; }
      loadLocked();
    } catch (RuntimeException e) {
      lastError = "start: " + e;
      local.clear();
      manifest = null;
    }
  }

  /** Do testów: promień wokół bieżącej pozycji, w którym paczki nie są usuwane przy braku miejsca. */
  synchronized void setProtectRadiusM(long m) { protectRadiusM = m; }

  /* ===================== odczyt ===================== */

  /** Drogi z paczek wokół punktu. Brakujące paczki są zamawiane w tle. Nie zgłasza wyjątków. */
  public Lookup lookup(double lat, double lon, int radiusM) { return lookup(lat, lon, radiusM, 0L); }

  /** Jak lookup, ale gdy brakuje paczek, czeka do waitMs na ich pobranie (np. przy pierwszym uruchomieniu w nowym miejscu). */
  public Lookup lookup(double lat, double lon, int radiusM, long waitMs) {
    long deadline = System.nanoTime() + waitMs * 1_000_000L;
    Lookup l = lookupOnce(lat, lon, radiusM);
    while (waitMs > 0 && !l.complete && l.missing + l.unknown > 0) {
      long left = (deadline - System.nanoTime()) / 1_000_000L;
      if (left <= 0) break;
      synchronized (this) {
        if (closed || (!draining && wanted.isEmpty() && !manifestWanted)) break;   // nic nie jedzie: czekanie nie ma sensu
        try { wait(Math.min(left, 150L)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
      l = lookupOnce(lat, lon, radiusM);
    }
    return l;
  }

  private synchronized Lookup lookupOnce(double lat, double lon, int radiusM) {
    try {
      if (disabled || closed) return new Lookup(Collections.<Road>emptyList(), false, 0, 0, 0, 1);
      curLat = lat; curLon = lon; haveCur = true;
      lastLat = lat; lastLon = lon; haveLast = true;
      maybeScheduleManifestLocked();
      maybeScheduleBulkLocked();
      double dLat = radiusM / 110540.0;
      double dLon = radiusM / (Math.cos(Math.toRadians(lat)) * 111320.0);
      int px0 = PackGrid.packX(lon - dLon), px1 = PackGrid.packX(lon + dLon);
      int py0 = PackGrid.packY(lat - dLat), py1 = PackGrid.packY(lat + dLat);
      if ((px1 - px0 + 1L) * (py1 - py0 + 1L) > 9) return new Lookup(Collections.<Road>emptyList(), false, 0, 0, 0, 1);
      List<Road> out = new ArrayList<Road>();
      Set<Long> seen = new HashSet<Long>();
      int have = 0, missing = 0, absent = 0, unknown = 0;
      long ownKey = PackGrid.key(PackGrid.packX(lon), PackGrid.packY(lat));
      boolean ownHave = false;
      for (int py = py0; py <= py1; py++) {
        for (int px = px0; px <= px1; px++) {
          long key = PackGrid.key(px, py);
          RoadDb db = localDbLocked(key);
          if (db != null) {
            have++;
            if (key == ownKey) ownHave = true;
            LocalPack lp = local.get(key);
            if (lp != null) lp.usedWall = clock.wall();
            for (Road r : db.roadsNear(lat, lon, radiusM + 25)) if (seen.add(r.id)) out.add(r);
            if (db.corruptTiles() > 0) handleCorruptLocked(key);
            else checkStaleLocked(key);
          } else if (manifest == null) {
            unknown++;
          } else if (manifest.get(key) != null) {
            missing++;
            wantLocked(key, true);
          } else {
            absent++;
          }
        }
      }
      prefetchNeighborsLocked(lat, lon);
      // Kompletne, gdy mamy paczkę, w której stoi auto, a żadnej sąsiedniej z okna nie brakuje. Pusta komórka bez paczki
      // (morze, lasy) nie przeszkadza; gdy auto stoi w komórce bez paczki (np. za granicą), dane są niekompletne.
      boolean complete = ownHave && missing == 0 && unknown == 0;
      return new Lookup(near(out, lat, lon, radiusM + 25), complete, have, missing, absent, unknown);
    } catch (RuntimeException e) {
      lastError = "lookup: " + e;
      return new Lookup(Collections.<Road>emptyList(), false, 0, 0, 0, 1);
    }
  }

  /** Zostawia drogi, których obwiednia dotyka koła o promieniu radiusM (odsiewa resztę kafelków). */
  static List<Road> near(List<Road> roads, double lat, double lon, int radiusM) {
    double dLat = radiusM / 110540.0, dLon = radiusM / (Math.cos(Math.toRadians(lat)) * 111320.0);
    List<Road> out = new ArrayList<Road>();
    for (Road r : roads) {
      double minLa = 91, maxLa = -91, minLo = 361, maxLo = -361;
      for (int i = 0; i < r.points(); i++) {
        double a = r.lat[i], o = r.lon[i];
        if (a < minLa) minLa = a;
        if (a > maxLa) maxLa = a;
        if (o < minLo) minLo = o;
        if (o > maxLo) maxLo = o;
      }
      if (lat >= minLa - dLat && lat <= maxLa + dLat && lon >= minLo - dLon && lon <= maxLo + dLon) out.add(r);
    }
    return out;
  }

  private RoadDb localDbLocked(long key) {
    LocalPack lp = local.get(key);
    if (lp == null) {
      if (!installFromSeedLocked(key)) return null;
      lp = local.get(key);
      if (lp == null) return null;
    }
    RoadDb db = handles.get(key);
    if (db != null) return db;
    try {
      db = RoadDb.open(new File(packsDir, PackGrid.name(px(key), py(key))));
    } catch (IOException e) {
      dropLocalLocked(key, "uszkodzona paczka: " + e.getMessage());
      return null;
    } catch (RuntimeException e) {
      dropLocalLocked(key, "uszkodzona paczka: " + e);
      return null;
    }
    handles.put(key, db);
    return db;
  }

  /** Kopiuje paczkę z wbudowanych danych (szybko, lokalnie), sprawdzając rozmiar, sumę i strukturę. Zła paczka startowa jest pomijana na stałe. */
  private boolean installFromSeedLocked(long key) {
    if (seed == null || seedManifest == null || seedBad.contains(key) || closed || disabled) return false;
    Manifest.Entry se = seedManifest.get(key);
    if (se == null) return false;
    String name = PackGrid.name(px(key), py(key));
    File part = new File(packsDir, name + ".part"), dest = new File(packsDir, name);
    try {
      if (packsDir.getUsableSpace() < se.size + MIN_FREE_BYTES) { lastError = "za mało miejsca na paczkę startową"; return false; }
      if (!seed.copyPack(px(key), py(key), part)) { part.delete(); seedBad.add(key); return false; }
      CRC32 crc = new CRC32();
      byte[] data = FileIo.readAll(part);
      crc.update(data, 0, data.length);
      if (data.length != se.size || crc.getValue() != se.crc) throw new IOException("paczka startowa: zły rozmiar lub suma");
      if (se.sha != null && !se.sha.equals(PackSigning.shortSha256(PackSigning.newSha256().digest(data)))) throw new IOException("paczka startowa: zła suma SHA-256");
      RoadDb check = RoadDb.open(part);
      check.close();
      if (!replace(part, dest)) throw new IOException("nie można zainstalować paczki startowej");
      local.put(key, new LocalPack(se.size, se.crc, clock.wall()));
      seedInstalls++;
      evictLocked();
      saveMetaLocked();
      return local.containsKey(key);
    } catch (IOException e) {
      part.delete();
      seedBad.add(key);
      lastError = "seed: " + e.getMessage();
      return false;
    } catch (RuntimeException e) {
      part.delete();
      seedBad.add(key);
      lastError = "seed: " + e;
      return false;
    }
  }

  private void handleCorruptLocked(long key) {
    Integer n = corruptCount.get(key);
    int c = n == null ? 1 : n + 1;
    corruptCount.put(key, c);
    if (c > 2) return;   // pobrana ponownie też zła: nie kręcimy się w kółko
    dropLocalLocked(key, "uszkodzony kafelek");
  }

  private void checkStaleLocked(long key) {
    if (manifest == null) return;
    Manifest.Entry me = manifest.get(key);
    LocalPack lp = local.get(key);
    if (me != null && lp != null && (me.size != lp.size || me.crc != lp.crc)) wantLocked(key, false);
  }

  private void dropLocalLocked(long key, String why) {
    lastError = why;
    local.remove(key);
    RoadDb db = handles.remove(key);
    if (db != null) db.close();
    new File(packsDir, PackGrid.name(px(key), py(key))).delete();
    saveMetaLocked();
    if (manifest != null && manifest.get(key) != null) wantLocked(key, true);
  }

  /* ===================== zamawianie ===================== */

  private void prefetchNeighborsLocked(double lat, double lon) {
    if (manifest == null) return;
    int px = PackGrid.packX(lon), py = PackGrid.packY(lat);
    double west = px * (double) PackGrid.PACK_TILES * PackGrid.TILE_LON_MICRO / 1e6;
    double east = (px + 1) * (double) PackGrid.PACK_TILES * PackGrid.TILE_LON_MICRO / 1e6;
    double south = py * (double) PackGrid.PACK_TILES * PackGrid.TILE_LAT_MICRO / 1e6;
    double north = (py + 1) * (double) PackGrid.PACK_TILES * PackGrid.TILE_LAT_MICRO / 1e6;
    double mLon = Math.cos(Math.toRadians(lat)) * 111320.0, mLat = 110540.0;
    boolean w = (lon - west) * mLon < EDGE_M, e = (east - lon) * mLon < EDGE_M;
    boolean s = (lat - south) * mLat < EDGE_M, n = (north - lat) * mLat < EDGE_M;
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dy == 0) continue;
        if (dx < 0 && !w) continue;
        if (dx > 0 && !e) continue;
        if (dy < 0 && !s) continue;
        if (dy > 0 && !n) continue;
        long key = PackGrid.key(px + dx, py + dy);
        if (local.containsKey(key) || manifest.get(key) == null) continue;
        if (installFromSeedLocked(key)) continue;            // wystarczy kopia z aplikacji; nowszą wersję wskaże kontrola przy odczycie
        wantLocked(key, false);
      }
    }
  }

  private void wantLocked(long key, boolean isUrgent) {
    if (closed || disabled) return;
    Retry r = retry.get(key);
    if (r != null && clock.mono() < r.at) return;
    wanted.add(key);
    if (isUrgent) urgent.add(key);
    kickLocked();
  }

  private void maybeScheduleManifestLocked() {
    long wall = clock.wall();
    boolean due = manifest == null || wall - manifestChecked > MANIFEST_MAX_AGE_MS || wall < manifestChecked;
    if (due && !manifestWanted && clock.mono() >= manifestRetryAt) {
      manifestWanted = true;
      kickLocked();
    }
  }

  /** Co minutę sprawdza, czy można ruszyć z odświeżaniem w tle (np. po tym, jak pojawiła się dobra sieć albo zaczęła się nowa doba). */
  private void maybeScheduleBulkLocked() {
    if (draining || manifest == null || manifestIsSeed) return;
    long now = clock.mono();
    if (now - bulkCheckedAt < 60_000L) return;
    bulkCheckedAt = now;
    kickLocked();                       // wątek roboczy sam sprawdzi politykę i zakończy się, gdy nie ma czego robić
  }

  private void kickLocked() {
    if (draining || closed) return;
    draining = true;
    try {
      worker.execute(new Runnable() { @Override public void run() { drain(); } });
    } catch (RuntimeException e) {
      draining = false;
      lastError = "execute: " + e;
    }
  }

  /* ===================== wątek roboczy ===================== */

  private void drain() {
    while (true) {
      boolean doManifest = false, bulk = false;
      long key = 0;
      synchronized (this) {
        if (closed) { draining = false; notifyAll(); return; }
        if (manifestWanted) { doManifest = true; manifestWanted = false; }
        else {
          Long next = null;
          for (Long k : urgent) if (wanted.contains(k)) { next = k; break; }
          if (next == null && !wanted.isEmpty()) next = wanted.iterator().next();
          if (next == null) {
            next = nextBulkLocked();
            if (next != null) bulk = true;
          }
          if (next == null) { draining = false; notifyAll(); return; }
          key = next;
        }
      }
      try {
        if (doManifest) fetchManifest(); else fetchPack(key, bulk);
      } catch (Throwable t) {
        synchronized (this) {
          lastError = "worker: " + t;
          if (!doManifest) failLocked(key, t.toString(), false);
          else manifestFailLocked(t.toString());
        }
      }
      synchronized (this) { notifyAll(); }
    }
  }

  private static final long DAY_MS = 24L * 3600 * 1000;

  /**
   * Następna paczka do odświeżenia w tle: taka, której wersja na serwerze różni się od naszej (zainstalowanej albo startowej),
   * najbliższa ostatniej znanej pozycji. Null, gdy nie ma czego robić, sieć nie jest dobra albo skończył się dzienny limit.
   * Dopóki mamy tylko wbudowany spis (nie widzieliśmy serwera), niczego nie ruszamy.
   */
  private Long nextBulkLocked() {
    if (manifest == null || manifestIsSeed || (!haveCur && !haveLast) || closed || disabled) return null;
    try {
      if (!policy.bulkAllowed()) return null;
      long budget = policy.bulkBudgetBytes();
      rollBudgetDayLocked();
      if (budgetBytes >= budget) return null;
      double lat = haveCur ? curLat : lastLat, lon = haveCur ? curLon : lastLon;
      Long best = null;
      double bd = Double.MAX_VALUE;
      long mono = clock.mono();
      for (Long key : manifest.keys()) {
        Manifest.Entry me = manifest.get(key);
        LocalPack lp = local.get(key);
        boolean stale;
        if (lp != null) stale = lp.size != me.size || lp.crc != me.crc;
        else {
          Manifest.Entry se = seedManifest == null ? null : seedManifest.get(key);
          stale = se != null && !seedBad.contains(key) && (se.size != me.size || se.crc != me.crc);
        }
        if (!stale) continue;
        Retry r = retry.get(key);
        if (r != null && mono < r.at) continue;
        double d = distToPackM(key, lat, lon);
        if (d < bd) { bd = d; best = key; }
      }
      if (best != null && budgetBytes + manifest.get(best).size > budget) return null;
      return best;
    } catch (RuntimeException e) {
      lastError = "bulk: " + e;
      return null;
    }
  }

  private void rollBudgetDayLocked() {
    long day = clock.wall() / DAY_MS;
    if (day != budgetDay) { budgetDay = day; budgetBytes = 0; }
  }

  private void fetchManifest() {
    File part = new File(dir, "manifest.json.part");
    String text;
    Manifest m;
    try {
      client.download(baseUrl + "manifest.json", part, 4_000_000L, -1);
      byte[] raw = FileIo.readAll(part);
      if (verifier != null) checkSignature(raw);
      text = new String(raw, UTF8);
      m = Manifest.parse(text);
    } catch (RoadSource.FetchException e) {
      synchronized (this) { manifestFailLocked(e.getMessage()); }
      part.delete();
      return;
    } catch (IOException e) {
      synchronized (this) { manifestFailLocked("odczyt: " + e); }
      part.delete();
      return;
    } catch (IllegalArgumentException e) {
      synchronized (this) { manifestFailLocked(e.getMessage()); }
      part.delete();
      return;
    }
    synchronized (this) {
      File f = new File(dir, "manifest.json");
      if (!replace(part, f)) { manifestFailLocked("nie można zapisać manifestu"); part.delete(); return; }
      manifest = m;
      manifestIsSeed = false;
      manifestChecked = clock.wall();
      manifestFailures = 0;
      manifestFetches++;
      saveMetaLocked();
      // paczki zmienione na serwerze zamawiamy od razu dla tych, które mamy (tylko bliskie bieżącej pozycji)
      if (haveCur) {
        for (Map.Entry<Long, LocalPack> e : new ArrayList<Map.Entry<Long, LocalPack>>(local.entrySet())) {
          Manifest.Entry me = m.get(e.getKey());
          if (me != null && (me.size != e.getValue().size || me.crc != e.getValue().crc)
              && distToPackM(e.getKey(), curLat, curLon) < 25_000) wantLocked(e.getKey(), false);
        }
      }
    }
  }

  /** Pobiera manifest.sig i sprawdza podpis spisu. Zgłasza IllegalArgumentException, gdy podpis jest zły albo go brak. */
  private void checkSignature(byte[] manifestBytes) throws RoadSource.FetchException, IOException {
    File sigPart = new File(dir, "manifest.sig.part");
    try {
      client.download(baseUrl + "manifest.sig", sigPart, 2_000L, -1);
      byte[] sig = PackSigning.signatureFromFileText(FileIo.readAll(sigPart));
      if (sig == null || !verifier.ok(manifestBytes, sig)) throw new IllegalArgumentException("zły podpis spisu");
    } finally {
      sigPart.delete();
    }
  }

  private void manifestFailLocked(String why) {
    lastError = "manifest: " + why;
    manifestFailures++;
    long backoff = Math.min(MAX_BACKOFF_MS, 15_000L << Math.min(manifestFailures - 1, 8));
    manifestRetryAt = clock.mono() + backoff;
  }

  private void fetchPack(long key, boolean bulk) {
    Manifest.Entry me;
    synchronized (this) {
      me = manifest == null ? null : manifest.get(key);
      if (me == null) { wanted.remove(key); urgent.remove(key); return; }
      LocalPack lp = local.get(key);
      if (lp != null && lp.size == me.size && lp.crc == me.crc) { wanted.remove(key); urgent.remove(key); return; }   // już aktualna
    }
    String name = PackGrid.name(px(key), py(key));
    File part = new File(packsDir, name + ".part");
    if (packsDir.getUsableSpace() < me.size + MIN_FREE_BYTES) {
      synchronized (this) { failLocked(key, "za mało miejsca na dysku", false); }
      return;
    }
    try {
      PackClient.Result r = client.download(baseUrl + name, part, Manifest.MAX_PACK_BYTES, me.size);
      if (r.size != me.size || r.crc != me.crc) throw new RoadSource.FetchException("suma kontrolna paczki się nie zgadza");
      if (me.sha != null && !me.sha.equals(r.sha)) throw new RoadSource.FetchException("suma SHA-256 paczki się nie zgadza");
      RoadDb check = RoadDb.open(part);   // zła zawartość nie zostanie zainstalowana
      check.close();
    } catch (RoadSource.FetchException e) {
      part.delete();
      synchronized (this) { failLocked(key, e.getMessage(), e.rateLimited); }
      return;
    } catch (IOException e) {
      part.delete();
      synchronized (this) { failLocked(key, "paczka nie przeszła kontroli: " + e.getMessage(), false); }
      return;
    }
    synchronized (this) {
      File dest = new File(packsDir, name);
      if (!replace(part, dest)) { part.delete(); failLocked(key, "nie można zainstalować paczki", false); return; }
      RoadDb old = handles.remove(key);
      if (old != null) old.close();
      local.put(key, new LocalPack(me.size, me.crc, clock.wall()));
      corruptCount.remove(key);
      retry.remove(key);
      wanted.remove(key);
      urgent.remove(key);
      downloads++;
      if (bulk) { bulkDownloads++; rollBudgetDayLocked(); budgetBytes += me.size; }
      evictLocked();
      saveMetaLocked();
    }
  }

  private void failLocked(long key, String why, boolean rateLimited) {
    lastError = "paczka: " + why;
    downloadFailures++;
    Retry r = retry.get(key);
    if (r == null) { r = new Retry(); retry.put(key, r); }
    r.failures++;
    long backoff = Math.min(MAX_BACKOFF_MS, 20_000L << Math.min(r.failures - 1, 7));
    if (rateLimited) backoff = Math.max(backoff, 120_000L);
    r.at = clock.mono() + backoff;
    wanted.remove(key);
    urgent.remove(key);
    new File(packsDir, PackGrid.name(px(key), py(key)) + ".part").delete();
  }

  private static boolean replace(File from, File to) {
    if (from.renameTo(to)) return true;
    if (to.exists() && !to.delete()) return false;
    return from.renameTo(to);
  }

  /* ===================== miejsce na dysku ===================== */

  private void evictLocked() {
    long total = 0;
    for (LocalPack p : local.values()) total += p.size;
    if (total <= quota) return;
    List<Map.Entry<Long, LocalPack>> list = new ArrayList<Map.Entry<Long, LocalPack>>(local.entrySet());
    Collections.sort(list, new Comparator<Map.Entry<Long, LocalPack>>() {
      @Override public int compare(Map.Entry<Long, LocalPack> a, Map.Entry<Long, LocalPack> b) {
        return a.getValue().usedWall < b.getValue().usedWall ? -1 : a.getValue().usedWall > b.getValue().usedWall ? 1 : 0;
      }
    });
    long target = (long) (quota * 0.85);
    for (Map.Entry<Long, LocalPack> e : list) {
      if (total <= target) break;
      long key = e.getKey();
      if (haveCur && distToPackM(key, curLat, curLon) < protectRadiusM) continue;   // okolice bieżącej pozycji zostają
      total -= e.getValue().size;
      local.remove(key);
      RoadDb db = handles.remove(key);
      if (db != null) db.close();
      new File(packsDir, PackGrid.name(px(key), py(key))).delete();
    }
  }

  static double distToPackM(long key, double lat, double lon) {
    double dLat = (PackGrid.centerLat(py(key)) - lat) * 110540.0;
    double dLon = (PackGrid.centerLon(px(key)) - lon) * Math.cos(Math.toRadians(lat)) * 111320.0;
    return Math.sqrt(dLat * dLat + dLon * dLon);
  }

  static int px(long key) { return (int) (key & 0xFFFFFFFFL); }
  static int py(long key) { return (int) (key >> 32); }

  /* ===================== trwałość ===================== */

  private void loadLocked() {
    // sprzątanie niedokończonych pobrań
    File[] files = packsDir.listFiles();
    Map<String, LocalPack> fromMeta = new HashMap<String, LocalPack>();
    File metaFile = new File(dir, "meta.json");
    if (metaFile.isFile()) {
      try {
        Map<String, Object> m = Json.obj(Json.parse(new String(FileIo.readAll(metaFile), UTF8)));
        if (m != null) {
          manifestChecked = (long) Json.num(m.get("checked"), 0);
          budgetDay = (long) Json.num(m.get("day"), 0);
          budgetBytes = (long) Json.num(m.get("dayBytes"), 0);
          if (m.get("lat") != null && m.get("lon") != null) {
            lastLat = Json.num(m.get("lat"), 0); lastLon = Json.num(m.get("lon"), 0);
            haveLast = Math.abs(lastLat) <= 90 && Math.abs(lastLon) <= 180 && (lastLat != 0 || lastLon != 0);
          }
          List<Object> arr = Json.arr(m.get("packs"));
          if (arr != null) for (Object o : arr) {
            List<Object> p = Json.arr(o);
            if (p == null || p.size() != 5) continue;
            int x = (int) Json.num(p.get(0), 0), y = (int) Json.num(p.get(1), 0);
            fromMeta.put(PackGrid.name(x, y), new LocalPack((long) Json.num(p.get(2), -1), (long) Json.num(p.get(3), -1), (long) Json.num(p.get(4), 0)));
          }
        }
      } catch (IOException e) {
        lastError = "meta: " + e;
      } catch (RuntimeException e) {
        lastError = "meta: " + e;
      }
    }
    if (files != null) {
      for (File f : files) {
        String n = f.getName();
        if (!n.endsWith(".rdb")) { f.delete(); continue; }
        LocalPack lp = fromMeta.get(n);
        long[] xy = parseName(n);
        if (lp == null || xy == null || lp.size != f.length()) { f.delete(); continue; }   // nie wiemy, co to za plik: do wyrzucenia
        local.put(PackGrid.key((int) xy[0], (int) xy[1]), lp);
      }
    }
    File mf = new File(dir, "manifest.json");
    new File(dir, "manifest.json.part").delete();
    if (mf.isFile()) {
      try {
        manifest = Manifest.parse(new String(FileIo.readAll(mf), UTF8));
      } catch (IOException e) {
        mf.delete();
      } catch (IllegalArgumentException e) {
        mf.delete();
      }
    }
    if (seed != null) {
      try {
        seedManifest = seed.manifest();
      } catch (RuntimeException e) {
        lastError = "seed manifest: " + e;
      }
      // wbudowany spis zastępuje brakujący albo starszy (nowa wersja aplikacji z nowszymi danymi); do pierwszego kontaktu z serwerem
      if (seedManifest != null && (manifest == null || seedManifest.builtSec > manifest.builtSec)) {
        manifest = seedManifest;
        manifestIsSeed = true;
        manifestChecked = 0;
      }
    }
    if (manifest == null) manifestChecked = 0;
  }

  /** "p_123_456.rdb" -> {123, 456}, inaczej null. */
  static long[] parseName(String n) {
    if (!n.startsWith("p_") || !n.endsWith(".rdb")) return null;
    String[] parts = n.substring(2, n.length() - 4).split("_");
    if (parts.length != 2) return null;
    try {
      return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void saveMetaLocked() {
    try {
      StringBuilder b = new StringBuilder(local.size() * 40 + 64);
      b.append("{\"checked\":").append(manifestChecked).append(",\"day\":").append(budgetDay).append(",\"dayBytes\":").append(budgetBytes);
      if (haveLast) b.append(",\"lat\":").append(lastLat).append(",\"lon\":").append(lastLon);
      b.append(",\"packs\":[");
      boolean first = true;
      for (Map.Entry<Long, LocalPack> e : local.entrySet()) {
        if (!first) b.append(',');
        first = false;
        LocalPack p = e.getValue();
        b.append('[').append(px(e.getKey())).append(',').append(py(e.getKey())).append(',').append(p.size).append(',').append(p.crc)
         .append(',').append(p.usedWall).append(']');
      }
      b.append("]}");
      File tmp = new File(dir, "meta.json.tmp");
      FileOutputStream o = new FileOutputStream(tmp);
      try { o.write(b.toString().getBytes(UTF8)); } finally { o.close(); }
      replace(tmp, new File(dir, "meta.json"));
    } catch (IOException e) {
      lastError = "zapis meta: " + e;
    }
  }

  /** Zapis czasów użycia (np. przy wyjściu z widoku). */
  public synchronized void flush() {
    if (!disabled) saveMetaLocked();
  }

  /** Zamknięcie: zwalnia pliki, wątek roboczy kończy po bieżącym zadaniu. */
  public synchronized void close() {
    closed = true;
    for (RoadDb db : handles.values()) db.close();
    handles.clear();
    if (!disabled) saveMetaLocked();
    notifyAll();
  }

  /** Usuwa wszystkie pobrane dane (ustawienia: „wyczyść dane map”). */
  public synchronized void clearAll() {
    for (RoadDb db : handles.values()) db.close();
    handles.clear();
    local.clear();
    retry.clear();
    wanted.clear();
    urgent.clear();
    File[] files = packsDir.listFiles();
    if (files != null) for (File f : files) f.delete();
    saveMetaLocked();
  }

  /* ===================== diagnostyka ===================== */

  public synchronized int packCount() { return local.size(); }

  public synchronized long bytesOnDisk() {
    long t = 0;
    for (LocalPack p : local.values()) t += p.size;
    return t;
  }

  public synchronized boolean hasManifest() { return manifest != null; }

  public synchronized long dataBuiltSec() { return manifest == null ? 0 : manifest.builtSec; }

  public synchronized boolean busy() { return draining || manifestWanted || !wanted.isEmpty(); }

  public synchronized int downloadsDone() { return downloads; }

  public synchronized int manifestFetchesDone() { return manifestFetches; }

  public synchronized boolean isLocal(int px, int py) { return local.containsKey(PackGrid.key(px, py)); }

  public synchronized String diagnostics() {
    return "paczki=" + local.size() + " (" + bytesOnDisk() / 1024 + " kB) pobrane=" + downloads + " błędy=" + downloadFailures
        + (seedManifest != null ? " startowe=" + seedInstalls : "") + (bulkDownloads > 0 ? " w tle=" + bulkDownloads : "")
        + " manifest=" + (manifest == null ? "brak" : manifest.size() + " paczek" + (manifestIsSeed ? " (startowy)" : "")) + " ostatni błąd=" + lastError;
  }

  /** Paczki zainstalowane z wbudowanych danych i pobrane w tle (do okna ustawień i testów). */
  public synchronized int seedInstalls() { return seedInstalls; }

  public synchronized int bulkDownloadsDone() { return bulkDownloads; }

  /** Dane są "świeże": spis z serwera sprawdzony w ciągu ostatnich 7 dni (wtedy, przy sieci, znak ma czerwony pierścień). */
  public synchronized boolean dataFresh() {
    return manifest != null && !manifestIsSeed && manifestChecked > 0 && clock.wall() - manifestChecked < 7L * DAY_MS;
  }

  /** Czy znamy już spis z serwera (a nie tylko wbudowany). */
  public synchronized boolean hasServerManifest() { return manifest != null && !manifestIsSeed; }

  /** Liczba radarów w danych, według spisu z serwera albo wbudowanego (0, gdy brak). */
  public synchronized int camerasInData() { return manifest == null ? 0 : manifest.cameras; }

  /**
   * Radary (fotoradary, odcinkowe pomiary, kamery na czerwonym świetle) w promieniu radiusM metrów z paczek lokalnych lub wbudowanych.
   * Niczego nie pobiera z sieci. Puste, gdy paczki nie ma. Nie zgłasza wyjątków.
   */
  public synchronized List<Camera> camerasNear(double lat, double lon, int radiusM) {
    List<Camera> out = new ArrayList<Camera>();
    try {
      if (disabled || closed) return out;
      double dLat = radiusM / 110540.0;
      double dLon = radiusM / (Math.cos(Math.toRadians(lat)) * 111320.0);
      int px0 = PackGrid.packX(lon - dLon), px1 = PackGrid.packX(lon + dLon);
      int py0 = PackGrid.packY(lat - dLat), py1 = PackGrid.packY(lat + dLat);
      if ((px1 - px0 + 1L) * (py1 - py0 + 1L) > 9) return out;
      for (int py = py0; py <= py1; py++) {
        for (int px = px0; px <= px1; px++) {
          RoadDb db = localDbLocked(PackGrid.key(px, py));
          if (db == null) continue;
          out.addAll(db.camerasNear(lat, lon, radiusM));
        }
      }
    } catch (RuntimeException e) {
      lastError = "radary: " + e;
    }
    return out;
  }
}
