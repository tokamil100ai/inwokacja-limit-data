package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.Camera;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Wykaz urządzeń rejestrujących Inspekcji Transportu Drogowego (zbiór "Dane dotyczące urządzeń rejestrujących w tym ich lokalizacji"
 * na dane.gov.pl, licencja CC0). Plik CSV w kodowaniu cp1250 ze średnikami: rodzaj urządzenia, kod lokalizacji, współrzędne początku
 * i (dla odcinkowego pomiaru) końca, długość odcinka w obu kierunkach. Zamienia wiersze na [Camera].
 */
public final class ItdCsv {
  /** Numery z wykazu ITD zaczynają się od tego progu, żeby nie zderzyły się z numerami węzłów OSM (te są poniżej 2^35). */
  static final long ID_BASE = 1L << 40;

  public static final class Row {
    public String kind = "", code = "", serial = "", place = "";
    public double lat1 = Double.NaN, lon1 = Double.NaN, lat2 = Double.NaN, lon2 = Double.NaN;
    public int lenFwd, lenBack;
  }

  public static final class Result {
    public final List<Row> rows = new ArrayList<Row>();
    public int skipped;
  }

  private ItdCsv() { }

  public static Result read(File f) throws IOException {
    BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("windows-1250")));
    try {
      Result res = new Result();
      String header = in.readLine();
      if (header == null) throw new IOException("pusty plik ITD");
      if (header.startsWith("﻿")) header = header.substring(1);
      List<String> h = split(header);
      Map<String, Integer> col = new HashMap<String, Integer>();
      for (int i = 0; i < h.size(); i++) col.put(norm(h.get(i)), i);
      int cKind = need(col, "rodzaj urzadzenia"), cCode = need(col, "kod lokalizacji");
      int cLat1 = need(col, "szerokosc geograficzna - poczatek"), cLon1 = need(col, "dlugosc geograficzna - poczatek");
      int cLat2 = col.containsKey("szerokosc geograficzna - koniec") ? col.get("szerokosc geograficzna - koniec") : -1;
      int cLon2 = col.containsKey("dlugosc geograficzna - koniec") ? col.get("dlugosc geograficzna - koniec") : -1;
      int cSerial = col.containsKey("numer seryjny") ? col.get("numer seryjny") : -1;
      int cPlace = col.containsKey("miejscowosc") ? col.get("miejscowosc") : -1;
      int cLenF = -1, cLenB = -1;
      for (Map.Entry<String, Integer> e : col.entrySet()) {
        if (e.getKey().startsWith("dlugosc odcinka kierunek p-t")) cLenF = e.getValue();
        if (e.getKey().startsWith("dlugosc odcinka kierunek t-p")) cLenB = e.getValue();
      }
      String line;
      while ((line = in.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        List<String> c = split(line);
        Row r = new Row();
        r.kind = norm(get(c, cKind));
        r.code = get(c, cCode).trim();
        r.serial = get(c, cSerial).trim();
        r.place = get(c, cPlace).trim();
        r.lat1 = num(get(c, cLat1)); r.lon1 = num(get(c, cLon1));
        r.lat2 = num(get(c, cLat2)); r.lon2 = num(get(c, cLon2));
        r.lenFwd = (int) Math.round(num0(get(c, cLenF)));
        r.lenBack = (int) Math.round(num0(get(c, cLenB)));
        if (!inPoland(r.lat1, r.lon1) || r.kind.isEmpty()) { res.skipped++; continue; }
        res.rows.add(r);
      }
      return res;
    } finally {
      in.close();
    }
  }

  /**
   * Wiersze -> urządzenia. Odcinkowy pomiar daje początek i koniec w kierunku jazdy; wiersz z długością w obu kierunkach
   * oznacza pomiar w obu kierunkach, więc dostaje drugą parę (od końca do początku). Wiersze bez drugiego końca odcinka są pomijane.
   */
  public static List<Camera> toCameras(Result res) {
    List<Camera> out = new ArrayList<Camera>();
    for (Row r : res.rows) {
      String key = r.code + "|" + r.serial + "|" + r.kind + "|" + r.lat1 + "|" + r.lon1;
      if (r.kind.contains("odcinkow")) {
        if (!inPoland(r.lat2, r.lon2)) continue;
        boolean both = r.lenFwd > 0 && r.lenBack > 0;
        long p1 = ID_BASE + crc("P|" + key);
        out.add(new Camera(ID_BASE + crc("S|" + key), Camera.SECTION_START, r.lat1, r.lon1, Camera.DIR_ANY, 0,
            r.lenFwd > 0 ? r.lenFwd : r.lenBack, Camera.SRC_ITD, p1));
        out.add(new Camera(ID_BASE + crc("E|" + key), Camera.SECTION_END, r.lat2, r.lon2, Camera.DIR_ANY, 0, 0, Camera.SRC_ITD, p1));
        if (both) {
          long p2 = ID_BASE + crc("Q|" + key);
          out.add(new Camera(ID_BASE + crc("S2|" + key), Camera.SECTION_START, r.lat2, r.lon2, Camera.DIR_ANY, 0, r.lenBack, Camera.SRC_ITD, p2));
          out.add(new Camera(ID_BASE + crc("E2|" + key), Camera.SECTION_END, r.lat1, r.lon1, Camera.DIR_ANY, 0, 0, Camera.SRC_ITD, p2));
        }
      } else if (r.kind.contains("kolej")) {
        out.add(new Camera(ID_BASE + crc("C|" + key), Camera.RAIL_LIGHT, r.lat1, r.lon1, Camera.DIR_ANY, 0, 0, Camera.SRC_ITD, 0));
      } else if (r.kind.contains("czerwone")) {
        out.add(new Camera(ID_BASE + crc("C|" + key), Camera.RED_LIGHT, r.lat1, r.lon1, Camera.DIR_ANY, 0, 0, Camera.SRC_ITD, 0));
      } else if (r.kind.contains("punktow")) {
        out.add(new Camera(ID_BASE + crc("C|" + key), Camera.SPEED, r.lat1, r.lon1, Camera.DIR_ANY, 0, 0, Camera.SRC_ITD, 0));
      }
    }
    return out;
  }

  static boolean inPoland(double lat, double lon) {
    return lat >= 48.9 && lat <= 55.0 && lon >= 14.0 && lon <= 24.3;
  }

  private static long crc(String s) {
    CRC32 c = new CRC32();
    byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    c.update(b, 0, b.length);
    return c.getValue();
  }

  private static int need(Map<String, Integer> col, String name) throws IOException {
    Integer i = col.get(name);
    if (i == null) throw new IOException("w wykazie ITD brak kolumny: " + name);
    return i;
  }

  private static String get(List<String> c, int i) { return i >= 0 && i < c.size() ? c.get(i) : ""; }

  private static double num(String s) {
    if (s == null) return Double.NaN;
    s = s.trim().replace(',', '.');
    if (s.isEmpty()) return Double.NaN;
    try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
  }

  private static double num0(String s) {
    double d = num(s);
    return Double.isNaN(d) || d < 0 || d > 200_000 ? 0 : d;
  }

  /** Nazwa kolumny/rodzaju bez polskich znaków, małymi literami, ze spacjami zredukowanymi. */
  static String norm(String s) {
    if (s == null) return "";
    String t = java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    t = t.replace('ł', 'l');
    return t.replaceAll("\\s+", " ");
  }

  /** Podział linii CSV po średnikach z obsługą cudzysłowów. */
  static List<String> split(String line) {
    List<String> out = new ArrayList<String>();
    StringBuilder cur = new StringBuilder();
    boolean q = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (q) {
        if (ch == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false;
        } else cur.append(ch);
      } else if (ch == '"') q = true;
      else if (ch == ';') { out.add(cur.toString()); cur.setLength(0); }
      else cur.append(ch);
    }
    out.add(cur.toString());
    return out;
  }
}
