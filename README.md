# Dane dróg do widżetu „Limit prędkości”

Automat (GitHub Actions) codziennie pobiera plik Polski z Geofabrik, wyciąga z niego drogi z limitami prędkości,
tnie je na małe paczki (około 11 x 10 km) i publikuje przez GitHub Pages:

- `v1/manifest.json` – spis paczek z rozmiarami i sumami kontrolnymi oraz czasem danych,
- `v1/p_X_Y.rdb` – paczki dróg,
- `v1/penalties.json` – tabela mandatów (do ręcznej aktualizacji bez zmiany aplikacji).

Aplikacja pobiera tylko te paczki, w których akurat jest albo za chwilę będzie, sprawdza ich sumy kontrolne i trzyma na dysku.
Paczka zmienia się tylko wtedy, gdy zmieniły się drogi w jej obszarze, więc dzienna aktualizacja to zwykle niewiele plików.

## Licencja danych

Zawiera informacje z OpenStreetMap, © autorzy OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Paczki są bazą pochodną i są udostępniane na tej samej licencji.

## Zmiana tabeli mandatów

Edytuj `penalties.json`, podnieś `version` i zrób commit. Najbliższy przebieg automatu (albo ręczne uruchomienie
„Dane dróg (codziennie)” w zakładce Actions) opublikuje nową wersję. Aplikacja sprawdza tabelę raz na dobę.
Tabela jest walidowana w aplikacji; zły plik jest ignorowany i zostaje poprzednia tabela.

## Kod

`src/` to ta sama część kodu co w aplikacji (`core`) oraz narzędzia budujące (`tools`). Budowa lokalna:

```
javac -d build $(find src -name '*.java')
java -Xmx12g -cp build altergames.carlauncher.limit.tools.RdbBuilder poland.osm.pbf out/v1 2000
```
