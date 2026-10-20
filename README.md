# Dane dróg do widżetu „Limit prędkości”

Automat (GitHub Actions) codziennie pobiera plik Polski z Geofabrik oraz wykaz urządzeń rejestrujących ITD (dane.gov.pl, CC0),
wyciąga z nich drogi z limitami prędkości i fotoradary (punktowe, odcinkowy pomiar prędkości, kamery na czerwonym świetle),
łączy oba źródła radarów w jedną listę, tnie wszystko na małe paczki (około 11 x 10 km) i publikuje przez GitHub Pages:

- `v2/manifest.json` – spis paczek z rozmiarami i sumami kontrolnymi, czasem danych i liczbą radarów,
- `v2/p_X_Y.rdb` – paczki dróg i radarów (format v2; dawny format v1 został wycofany (katalog `v1/` nie jest już publikowany)),
- `v2/manifest.sig` – podpis cyfrowy spisu (ECDSA P-256, szesnastkowo). Aplikacja przyjmuje tylko spis podpisany kluczem, którego część
  publiczna jest w `limit-public-key.hex` i w aplikacji; klucz prywatny jest wyłącznie w sekrecie `LIMIT_SIGNING_KEY` repozytorium
  i w kopii zapasowej właściciela (nie w tym repozytorium). Każdy wpis spisu ma też SHA-256 paczki, więc podmiana plików na hostingu
  nie przejdzie bez klucza,
- `v2/penalties.json` – tabela mandatów (do ręcznej aktualizacji bez zmiany aplikacji).

Aplikacja pobiera tylko te paczki, w których akurat jest albo za chwilę będzie, sprawdza ich sumy kontrolne i trzyma na dysku.
Paczka zmienia się tylko wtedy, gdy zmieniły się drogi w jej obszarze, więc dzienna aktualizacja to zwykle niewiele plików.

## Licencja danych

Zawiera informacje z OpenStreetMap, © autorzy OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Paczki są bazą pochodną i są udostępniane na tej samej licencji.
Lokalizacje urządzeń rejestrujących pochodzą też z wykazu Inspekcji Transportu Drogowego (zbiór „Rejestrator ruchu drogowego”, licencja CC0).

## Zmiana tabeli mandatów

Edytuj `penalties.json`, podnieś `version` i zrób commit. Najbliższy przebieg automatu (albo ręczne uruchomienie
„Dane dróg (codziennie)” w zakładce Actions) opublikuje nową wersję. Aplikacja sprawdza tabelę raz na dobę.
Tabela jest walidowana w aplikacji; zły plik jest ignorowany i zostaje poprzednia tabela.

## Kod

`src/` to ta sama część kodu co w aplikacji (`core`) oraz narzędzia budujące (`tools`). Budowa lokalna:

```
javac -d build $(find src -name '*.java')
python3 scripts/fetch_itd.py itd.csv
java -Xmx4g -cp build altergames.carlauncher.limit.tools.RdbBuilder --itd=itd.csv poland.osm.pbf out/v2 2000
```
