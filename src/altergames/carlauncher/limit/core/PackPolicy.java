package altergames.carlauncher.limit.core;

/**
 * Zasady pobierania aktualizacji "hurtowych" w tle, czyli odświeżania paczek spoza okolicy, w której akurat jedziemy.
 * Paczki potrzebne od razu (okolica auta) są pobierane zawsze, to pytanie dotyczy tylko reszty kraju.
 */
public interface PackPolicy {
  /** Czy teraz wolno pobierać w tle (dobra sieć, bez ograniczeń transmisji danych). Wywoływane z wątku roboczego, ma być szybkie. */
  boolean bulkAllowed();

  /** Ile bajtów na dobę wolno pobrać w tle. */
  long bulkBudgetBytes();

  /** Bez pobierania w tle (zachowanie domyślne, np. w testach). */
  PackPolicy NONE = new PackPolicy() {
    @Override public boolean bulkAllowed() { return false; }
    @Override public long bulkBudgetBytes() { return 0; }
  };
}
