package altergames.carlauncher.limit.core;

import java.io.File;
import java.io.IOException;

/**
 * Wbudowane dane startowe: paczki dróg i radarów dołączone do aplikacji (w aplikacji: zasoby APK). Dzięki nim znak pojawia się od razu,
 * także bez internetu i w miejscu, w którym jeszcze nigdy nie byliśmy. Magazyn kopiuje z nich tylko te paczki, które są potrzebne,
 * a potem traktuje je jak zwykłe pobrane: gdy serwer ma nowszą wersję, paczka jest odświeżana.
 */
public interface PackSeed {
  /** Spis wbudowanych paczek albo null, gdy aplikacja nie ma wbudowanych danych. */
  Manifest manifest();

  /** Kopiuje paczkę do pliku dest. Zwraca false, gdy takiej paczki nie ma we wbudowanych danych. */
  boolean copyPack(int px, int py, File dest) throws IOException;
}
