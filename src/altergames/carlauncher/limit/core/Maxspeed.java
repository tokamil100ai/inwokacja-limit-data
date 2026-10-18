package altergames.carlauncher.limit.core;

import java.util.Locale;

/**
 * Odczyt wartości tagu OpenStreetMap maxspeed (i pokrewnych) na km/h.
 * Zasada: w razie jakichkolwiek wątpliwości zwracamy -1 (nieznane), bo fałszywy alarm jest gorszy niż brak informacji.
 */
public final class Maxspeed {
  private Maxspeed() {}

  public static final int UNKNOWN = -1;

  /** Liczba km/h z tagu maxspeed albo -1. Obsługuje "50", "50 km/h" oraz oznaczenia polskie PL:urban, PL:rural, PL:motorway. */
  public static int parse(String raw) {
    if (raw == null) return UNKNOWN;
    String v = raw.trim().toLowerCase(Locale.ROOT);
    if (v.isEmpty() || v.length() > 16) return UNKNOWN;
    if (v.indexOf(';') >= 0 || v.indexOf('|') >= 0 || v.indexOf('@') >= 0) return UNKNOWN;   // kilka wartości, warunkowe
    switch (v) {
      case "pl:urban": return 50;
      case "pl:rural": return 90;
      case "pl:motorway": return 140;
      default: break;
    }
    if (v.endsWith("km/h")) v = v.substring(0, v.length() - 4).trim();
    if (v.isEmpty() || v.length() > 3) return UNKNOWN;
    int n = 0;
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c < '0' || c > '9') return UNKNOWN;     // mph, none, signals, walk, variable i inne
      n = n * 10 + (c - '0');
    }
    return validLimit(n) ? n : UNKNOWN;
  }

  /** Strefa: zone:maxspeed=PL:30 daje 30. */
  public static int parseZone(String raw) {
    if (raw == null) return UNKNOWN;
    String v = raw.trim().toLowerCase(Locale.ROOT);
    if (v.startsWith("pl:")) return parse(v.substring(3));
    return UNKNOWN;
  }

  /** Dopuszczalne limity w Polsce: wielokrotności 5 w zakresie 5..140. */
  public static boolean validLimit(int n) {
    return n >= 5 && n <= 140 && n % 5 == 0;
  }
}
