#!/usr/bin/env python3
"""Pobiera najnowszy wykaz urządzeń rejestrujących ITD (fotoradary, odcinkowy pomiar, kamery na czerwonym świetle) z dane.gov.pl.

Zbiór 4364 "Rejestrator ruchu drogowego" (licencja CC0). Wybiera najnowszy zasób o tytule zaczynającym się od
"Dane dotyczące urządzeń rejestrujących w tym ich lokalizacji", pobiera CSV (cp1250, separator ;) i sprawdza, czy wygląda
sensownie (kolumny, liczba wierszy), żeby zepsuty plik nie wyczyścił radarów z danych.
Użycie: fetch_itd.py wyjście.csv
"""
import csv
import io
import json
import sys
import time
import urllib.request

API = "https://api.dane.gov.pl/1.4/datasets/4364/resources?per_page=100&sort=-created"
TITLE_PREFIX = "Dane dotyczące urządzeń rejestrujących w tym ich lokalizacji"
NEEDED = ["rodzaj urządzenia", "kod lokalizacji", "szerokość geograficzna - początek", "długość geograficzna - początek"]
MIN_ROWS = 300


def get(url, accept=None):
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "inwokacja-limit-data/1", **({"Accept": accept} if accept else {})})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - sieć bywa kapryśna, próbujemy kilka razy
            last = e
            time.sleep(3 * (attempt + 1))
    raise SystemExit(f"nie udało się pobrać {url}: {last}")


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    listing = json.loads(get(API, "application/vnd.api+json"))
    cands = [r for r in listing.get("data", []) if r["attributes"].get("title", "").startswith(TITLE_PREFIX)]
    if not cands:
        raise SystemExit("w zbiorze nie ma zasobu z wykazem urządzeń")
    res = max(cands, key=lambda r: r["attributes"].get("created", ""))
    url = res["attributes"]["download_url"]
    raw = get(url)
    text = raw.decode("cp1250")
    rows = list(csv.reader(io.StringIO(text), delimiter=";"))
    header = [h.strip().lower() for h in rows[0]]
    missing = [n for n in NEEDED if n not in header]
    if missing:
        raise SystemExit(f"wykaz ITD: brak kolumn {missing}")
    if len(rows) - 1 < MIN_ROWS:
        raise SystemExit(f"wykaz ITD: podejrzanie mało wierszy ({len(rows) - 1})")
    with open(sys.argv[1], "wb") as f:
        f.write(raw)
    print(f"ITD: zasób {res['id']} ({res['attributes'].get('data_date')}), wierszy: {len(rows) - 1}")


if __name__ == "__main__":
    main()
