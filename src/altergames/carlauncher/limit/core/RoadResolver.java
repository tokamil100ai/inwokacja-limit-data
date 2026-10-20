package altergames.carlauncher.limit.core;

import java.util.Map;

/**
 * Zamiana tagów OpenStreetMap jednej drogi na Road z limitem.
 * Limit zapisany w OSM (maxspeed, maxspeed:forward/backward, zone:maxspeed) jest znakiem (Source.SIGN). Gdy go nie ma, w danych
 * zostaje SZACUNEK (Source.ESTIMATE), zawsze oznaczony: w aplikacji przerywany pierścień, a przełącznik w ustawieniach widżetu
 * (od 2026-10-20, decyzja Kamila) pozwala w ogóle nie pokazywać szacunków (wtedy droga bez zapisanego limitu ma znak "?").
 * Szacunki to: deklaracja mapującego (maxspeed:type / source:maxspeed = PL:urban, PL:rural...) oraz kilka reguł z prawa, które nie
 * zależą od terenu zabudowanego: autostrada 140, droga ekspresowa 120 (dwujezdniowa) lub 100, strefa zamieszkania 20 i ulica osiedlowa 50.
 * Drogi krajowe, wojewódzkie, powiatowe i gminne bez zapisu zostają bez limitu, bo 50 czy 90 zależy od terenu zabudowanego (patrz
 * LimitInference i UrbanInference: oba są prognozami). Droga bez limitu zostaje w bazie ("?"), żeby dopasowanie nie przeskakiwało
 * na sąsiednią drogę główną z innym limitem.
 */
public final class RoadResolver {
  private RoadResolver() {}

  /** Typy dróg, które w ogóle bierzemy pod uwagę. */
  public static boolean isDrivable(String hw) {
    if (hw == null) return false;
    switch (hw) {
      case "motorway": case "motorway_link":
      case "trunk": case "trunk_link":
      case "primary": case "primary_link":
      case "secondary": case "secondary_link":
      case "tertiary": case "tertiary_link":
      case "unclassified": case "residential": case "living_street": case "service":
        return true;
      default:
        return false;
    }
  }

  /** Zwraca null, jeśli droga nie nadaje się do dopasowania (np. droga serwisowa bez jawnego limitu). */
  public static Road fromTags(long id, Map<String, String> tags, double[] lat, double[] lon) {
    if (tags == null || lat == null || lon == null || lat.length < 2 || lat.length != lon.length) return null;
    String hw = tags.get("highway");
    if (!isDrivable(hw)) return null;
    if ("yes".equals(tags.get("area"))) return null;   // powierzchnia (parking, plac), nie droga

    int oneway = 0;
    String ow = tags.get("oneway");
    if ("yes".equals(ow) || "true".equals(ow) || "1".equals(ow)) oneway = 1;
    else if ("-1".equals(ow) || "reverse".equals(ow)) oneway = -1;
    else if ("motorway".equals(hw) || "motorway_link".equals(hw)) oneway = oneway == 0 && !"no".equals(ow) ? 1 : 0;
    if ("roundabout".equals(tags.get("junction")) && oneway == 0 && !"no".equals(ow)) oneway = 1;

    int fwd = Maxspeed.parse(tags.get("maxspeed:forward"));
    int bwd = Maxspeed.parse(tags.get("maxspeed:backward"));

    int limit = Maxspeed.parse(tags.get("maxspeed"));
    Road.Source src = Road.Source.NONE;
    if (limit != Maxspeed.UNKNOWN) {
      // jawna liczba: znak albo wpis mapującego; PL:urban itp. w samym maxspeed też uznajemy za wpis jawny
      src = Road.Source.SIGN;
    } else {
      int zone = Maxspeed.parseZone(tags.get("zone:maxspeed"));
      if (zone != Maxspeed.UNKNOWN) { limit = zone; src = Road.Source.SIGN; }
    }
    if (limit == Maxspeed.UNKNOWN) {
      // oznaczenie typu limitu bez liczby (np. source:maxspeed=PL:urban): to szacunek
      int t = Maxspeed.parse(tags.get("maxspeed:type"));
      if (t == Maxspeed.UNKNOWN) t = Maxspeed.parse(tags.get("source:maxspeed"));
      if (t != Maxspeed.UNKNOWN && isZoneToken(tags.get("maxspeed:type"), tags.get("source:maxspeed"))) {
        limit = t; src = Road.Source.ESTIMATE;
      }
    }
    if (limit == Maxspeed.UNKNOWN && fwd == Maxspeed.UNKNOWN && bwd == Maxspeed.UNKNOWN) {
      int est = estimate(hw, tags, oneway);
      if (est != Maxspeed.UNKNOWN) { limit = est; src = Road.Source.ESTIMATE; }
    }
    // droga serwisowa bez żadnej jawnej informacji o limicie: pomijamy, żeby parkingi i podjazdy nie zakłócały
    if ("service".equals(hw) && src != Road.Source.SIGN && fwd == Maxspeed.UNKNOWN && bwd == Maxspeed.UNKNOWN) return null;

    return new Road(id, hw, oneway, limit, src, fwd, bwd, lat, lon);
  }

  private static boolean isZoneToken(String a, String b) {
    return (a != null && a.trim().toLowerCase(java.util.Locale.ROOT).startsWith("pl:"))
        || (b != null && b.trim().toLowerCase(java.util.Locale.ROOT).startsWith("pl:"));
  }

  /** Szacunek z typu drogi, tylko w przypadkach jednoznacznych. -1 oznacza brak szacunku. */
  static int estimate(String hw, Map<String, String> tags, int oneway) {
    switch (hw) {
      case "motorway": return 140;
      case "living_street": return 20;
      case "residential": return 50;
      case "trunk": case "primary": case "secondary": {
        // droga ekspresowa (motorroad): dwujezdniowa 120, jednojezdniowa 100
        if ("yes".equals(tags.get("motorroad"))) return oneway != 0 ? 120 : 100;
        return Maxspeed.UNKNOWN;
      }
      default: return Maxspeed.UNKNOWN;
    }
  }
}
