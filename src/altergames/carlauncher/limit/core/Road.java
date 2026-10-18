package altergames.carlauncher.limit.core;

/** Odcinek drogi z geometrią i ustalonym limitem. Obiekt niemutowalny, bezpieczny do współdzielenia między wątkami. */
public final class Road {
  /** SIGN: limit wpisany w danych (znak lub jawna wartość), ESTIMATE: szacunek z typu drogi, NONE: brak informacji. */
  public enum Source { SIGN, ESTIMATE, NONE }

  public final long id;
  public final String highway;
  /** 0 dwukierunkowa, 1 jednokierunkowa zgodnie z kolejnością punktów, -1 jednokierunkowa odwrotnie. */
  public final int oneway;
  public final int limit;
  public final Source source;
  /** Limity zależne od kierunku (maxspeed:forward / backward), -1 gdy brak. */
  public final int limitFwd;
  public final int limitBwd;
  public final double[] lat;
  public final double[] lon;

  public Road(long id, String highway, int oneway, int limit, Source source, int limitFwd, int limitBwd,
              double[] lat, double[] lon) {
    this.id = id;
    this.highway = highway == null ? "" : highway;
    this.oneway = oneway;
    this.limit = limit;
    this.source = source == null ? Source.NONE : source;
    this.limitFwd = limitFwd;
    this.limitBwd = limitBwd;
    this.lat = lat;
    this.lon = lon;
  }

  public int points() { return lat.length; }
}
