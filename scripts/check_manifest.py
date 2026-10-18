#!/usr/bin/env python3
"""Kontrola wyniku budowy przed publikacją. Zły plik z Geofabrik nie może zastąpić dobrych danych."""
import json
import os
import sys

new_path = sys.argv[1]
old_path = sys.argv[2] if len(sys.argv) > 2 else None

with open(new_path, encoding="utf-8") as f:
    new = json.load(f)
packs = new["packs"]
total = sum(p[2] for p in packs)
print(f"nowy manifest: {len(packs)} paczek, {total / 1e6:.1f} MB, czas danych OSM {new['osm']}")

errors = []
if new.get("v") != 1:
    errors.append("zła wersja manifestu")
if len(packs) < 2000:
    errors.append(f"za mało paczek ({len(packs)}), Polska ma ich kilka tysięcy")
if total > 900e6:
    errors.append(f"za dużo danych ({total / 1e6:.0f} MB), limit GitHub Pages to 1 GB")
for p in packs:
    path = os.path.join(os.path.dirname(new_path), f"p_{p[0]}_{p[1]}.rdb")
    if not os.path.isfile(path) or os.path.getsize(path) != p[2]:
        errors.append(f"brak lub zły rozmiar pliku {path}")
        break

if old_path and os.path.isfile(old_path) and os.path.getsize(old_path) > 0:
    try:
        with open(old_path, encoding="utf-8") as f:
            old = json.load(f)
        old_total = sum(p[2] for p in old["packs"])
        print(f"poprzedni manifest: {len(old['packs'])} paczek, {old_total / 1e6:.1f} MB")
        if len(packs) < 0.9 * len(old["packs"]):
            errors.append("liczba paczek spadła o ponad 10 procent")
        if total < 0.85 * old_total:
            errors.append("rozmiar danych spadł o ponad 15 procent")
        same = {(p[0], p[1]): p[3] for p in old["packs"]}
        changed = sum(1 for p in packs if same.get((p[0], p[1])) != p[3])
        print(f"zmienionych lub nowych paczek: {changed} z {len(packs)}")
    except Exception as e:  # poprzedni manifest uszkodzony: nie blokujemy publikacji
        print(f"poprzedni manifest nieczytelny ({e}), pomijam porównanie")

if errors:
    for e in errors:
        print("BŁĄD:", e)
    sys.exit(1)
print("kontrola zaliczona")
