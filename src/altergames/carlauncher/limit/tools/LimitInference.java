package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.Maxspeed;
import altergames.carlauncher.limit.core.Road;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * UWAGA: to prognoza, nie dane z OSM; w budowie paczek jest domyślnie WYŁĄCZONE (RdbBuilder --neighbors), decyzja Kamila 2026-10-20.
 * Uzupełnianie brakujących limitów z sąsiednich odcinków tej samej drogi, żeby w aplikacji rzadziej widać było "?".
 * Zasada ostrożności: odcinek bez limitu dostaje wartość tylko wtedy, gdy ciąg takich odcinków (ta sama droga, czyli ten sam
 * numer albo nazwa i ten sam typ) jest z OBU stron zamknięty odcinkami z jawnym limitem i wszystkie te sąsiednie limity są równe.
 * Przykład: 50 | brak | 50 daje 50, a 50 | brak | 90 (wyjazd z miejscowości) zostaje bez limitu. Wynik jest oznaczony jako szacunek
 * (w aplikacji przerywany pierścień), nigdy jako znak.
 */
final class LimitInference {
  /** Koniec drogi i jej numer zapisujemy w jednej liczbie: identyfikator punktu w górnych bitach, numer drogi w dolnych 24. */
  private static final int IDX_BITS = 24;
  private static final long IDX_MASK = (1L << IDX_BITS) - 1;

  private LimitInference() { }

  /** Klucz ciągłości drogi z tagów: numer drogi, a gdy go brak nazwa; null, gdy droga nie ma żadnego (nie da się jej powiązać). */
  static String continuityKey(Map<String, String> tags) {
    String ref = tags.get("ref"), name = tags.get("name");
    if (ref != null && !ref.trim().isEmpty()) return "r:" + ref.trim();
    if (name != null && !name.trim().isEmpty()) return "n:" + name.trim();
    return null;
  }

  private static boolean eligible(String hw) {
    return "trunk".equals(hw) || "primary".equals(hw) || "secondary".equals(hw) || "tertiary".equals(hw) || "unclassified".equals(hw);
  }

  /**
   * Zwraca nową listę dróg (obiekty bez zmian poza uzupełnionymi). first/last: identyfikatory końcowych punktów każdej drogi,
   * key: klucz ciągłości lub null. filled[0] dostaje liczbę uzupełnionych odcinków.
   */
  static List<Road> apply(List<Road> roads, long[] first, long[] last, String[] key, int[] filled) {
    int n = roads.size();
    filled[0] = 0;
    if (n >= (1 << IDX_BITS)) return roads;                  // nierealnie dużo dróg w jednym pliku: bez uzupełniania
    // końce wszystkich dróg z kluczem, posortowane po punkcie: kolejne wpisy o tym samym punkcie to sąsiedzi
    long[] ep = new long[2 * n];
    int m = 0;
    for (int i = 0; i < n; i++) {
      if (key[i] == null || first[i] == last[i]) continue;
      ep[m++] = (first[i] << IDX_BITS) | i;
      ep[m++] = (last[i] << IDX_BITS) | i;
    }
    ep = Arrays.copyOf(ep, m);
    Arrays.sort(ep);

    boolean[] unknown = new boolean[n];
    for (int i = 0; i < n; i++) {
      Road r = roads.get(i);
      unknown[i] = key[i] != null && eligible(r.highway) && r.limit == Maxspeed.UNKNOWN
          && r.limitFwd == Maxspeed.UNKNOWN && r.limitBwd == Maxspeed.UNKNOWN && r.source == Road.Source.NONE;
    }

    // składowe: nieznane odcinki tej samej drogi styczne w punkcie końcowym
    int[] parent = new int[n];
    for (int i = 0; i < n; i++) parent[i] = i;
    for (int a = 0; a < m; ) {
      int b = a;
      long node = ep[a] >> IDX_BITS;
      while (b < m && (ep[b] >> IDX_BITS) == node) b++;
      for (int x = a; x < b; x++) {
        int ix = (int) (ep[x] & IDX_MASK);
        if (!unknown[ix]) continue;
        for (int y = x + 1; y < b; y++) {
          int iy = (int) (ep[y] & IDX_MASK);
          if (unknown[iy] && ix != iy && same(roads, key, ix, iy)) union(parent, ix, iy);
        }
      }
      a = b;
    }

    // wartość każdej składowej: wspólny limit sąsiadów na wszystkich jej końcach; -1 = odrzucona
    int[] value = new int[n];
    for (int a = 0; a < m; ) {
      int b = a;
      long node = ep[a] >> IDX_BITS;
      while (b < m && (ep[b] >> IDX_BITS) == node) b++;
      for (int x = a; x < b; x++) {
        int ix = (int) (ep[x] & IDX_MASK);
        if (!unknown[ix]) continue;
        int root = find(parent, ix);
        if (value[root] == -1) continue;
        boolean interior = false;
        int lim = 0;                                   // 0 brak, -1 sprzeczne
        for (int y = a; y < b; y++) {
          int iy = (int) (ep[y] & IDX_MASK);
          if (iy == ix || !same(roads, key, ix, iy)) continue;
          if (unknown[iy]) { interior = true; continue; }
          Road k = roads.get(iy);
          if (k.source != Road.Source.SIGN || k.limit <= 0) continue;
          if (lim == 0) lim = k.limit; else if (lim != k.limit) lim = -1;
        }
        if (interior) continue;                        // punkt wewnątrz ciągu, nie jego koniec
        if (lim <= 0) { value[root] = -1; continue; }  // koniec bez sąsiada z limitem albo sprzeczni sąsiedzi
        if (value[root] == 0) value[root] = lim;
        else if (value[root] != lim) value[root] = -1;
      }
      a = b;
    }

    List<Road> out = new ArrayList<Road>(n);
    int cnt = 0;
    for (int i = 0; i < n; i++) {
      Road r = roads.get(i);
      if (unknown[i]) {
        int v = value[find(parent, i)];
        if (v > 0) {
          out.add(new Road(r.id, r.highway, r.oneway, v, Road.Source.ESTIMATE, r.limitFwd, r.limitBwd, r.lat, r.lon));
          cnt++;
          continue;
        }
      }
      out.add(r);
    }
    filled[0] = cnt;
    return out;
  }

  private static boolean same(List<Road> roads, String[] key, int a, int b) {
    return key[a].equals(key[b]) && roads.get(a).highway.equals(roads.get(b).highway);
  }

  private static int find(int[] p, int x) {
    while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
    return x;
  }

  private static void union(int[] p, int a, int b) {
    int ra = find(p, a), rb = find(p, b);
    if (ra != rb) p[ra] = rb;
  }
}
