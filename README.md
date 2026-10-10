# Psie Parki — wybiegi dla psów w całej Polsce

Aplikacja na Androida, która pokazuje najbliższe wybiegi dla psów: listę od
najbliższego, mapę i szczegóły (ogrodzenie, oświetlenie, woda, agility, część dla
małych psów). Bez reklam, bez kont i bez serwera. Lokalizacja nie opuszcza telefonu.

| | |
|---|---|
| Nazwa w sklepie | Psie Parki |
| `applicationId` | `com.adminstack.psieparki` (**nieodwracalny** po pierwszej publikacji) |
| Wersja | 1.0.0 (versionCode 1), niewydana |
| Dane | OpenStreetMap (`leisure=dog_park`) + ręczne poprawki, odświeżane co tydzień |
| Mapa | MapLibre + kafelki OpenFreeMap (bez klucza API, bez limitów) |

## Jak to jest poskładane

```
scraper/build.py          OSM → data/parks.json (Overpass; walidacja kontraktu przed zapisem)
scraper/pbf.py            to samo z pliku .osm.pbf, gdy Overpass leży
scraper/normalize.py      tagi OSM → cechy wybiegu (tak / nie / brak danych)
scraper/manual.py         nakłada manual/poprawki.json
scraper/CONTRACT.md       format data/parks.json — wiążący dla apki
manual/poprawki.json      ręczne poprawki, ukryte i dodane wybiegi (edytuje człowiek)
data/parks.json           wynik; apka pobiera go z raw.githubusercontent.com raz na dobę
                          i ma jego kopię w APK na pierwsze uruchomienie

app/                      Kotlin + Compose, pakiet com.adminstack.psieparki
play/                     opisy, grafiki i zrzuty do Sklepu Play, skrypty, które je robią
PRIVACY.md                polityka prywatności (URL do Play Console)
```

## Na co dzień

```bash
pip install -r scraper/requirements.txt
python scraper/build.py                          # Overpass → data/parks.json
python scraper/pbf.py poland.osm.pbf -o raw.json # gdy Overpass nie odpowiada
python scraper/build.py --raw raw.json
python -m unittest discover -s scraper/tests -t scraper

./gradlew testDebugUnitTest                      # testy (czytają też prawdziwy data/parks.json)
./gradlew assembleDebug
./gradlew bundleRelease                          # AAB do sklepu, podpis z keystore.properties
```

CI: `.github/workflows/data.yml` co poniedziałek przebudowuje `data/parks.json`
(Overpass, a gdy padnie — wycinek Polski z Geofabrik) i commituje tylko, gdy zmienił się
jakiś wybieg. `app.yml` buduje i testuje apkę przy każdym PR.

## Zgłoszenia od użytkowników

„Zgłoś poprawkę” i „Brakuje wybiegu?” otwierają gotowego maila na office@admin-stack.com
z id wybiegu i współrzędnymi. Co z nim zrobić:

1. **Fakt o miejscu** (jest ogrodzenie, wybieg zlikwidowany, brakujący wybieg) — najlepiej
   poprawić w OpenStreetMap (link „Zobacz lub popraw w OpenStreetMap” w apce). Trafi do
   apki przy najbliższej aktualizacji i do każdej innej mapy.
2. **Coś, czego OSM nie przechowa** (dni adopcji, regulamin) albo pilna poprawka przed
   aktualizacją OSM — wpis w `manual/poprawki.json` (instrukcja w pliku), commit, push.

## Rzeczy, o których trzeba pamiętać

- **Danych jest mało.** W październiku 2026 OSM miał w Polsce 618 publicznych wybiegów:
  sporo w dużych miastach (Warszawa 46, Gdańsk 43, Kraków 40), prawie nic na wsi. Cechy
  są opisane rzadko (ogrodzenie: 262, woda: 0). Dlatego apka mówi „brak danych”, a nie
  „nie”, i dlatego zgłaszanie jest na wierzchu. Uzupełnianie danych to główna praca przy
  tym projekcie, nie kod.
- **Kafelki mapy z OpenFreeMap**, nie z tile.openstreetmap.org — zasady OSMF zabraniają
  używania ich serwera kafelków w aplikacjach. Atrybucja (OSM, OpenMapTiles) jest na mapie
  i w „Więcej”; nie usuwaj jej, licencja ODbL tego wymaga.
- **Dane muszą być publiczne** pod `ParksRepository.DATA_URL` — raw.githubusercontent.com
  nie poda pliku z prywatnego repo.
- **Keystore**: `keystore.properties` poza repo (jest w `.gitignore`), Play App Signing
  przy pierwszym wydaniu. Szczegóły w `play/PUBLISHING.md`.
