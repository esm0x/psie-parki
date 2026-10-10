# Psie Parki — wybiegi dla psów w całej Polsce

Aplikacja na Androida, która pokazuje najbliższe wybiegi dla psów: listę od
najbliższego, mapę i szczegóły (ogrodzenie, oświetlenie, woda, agility, część dla
małych psów). Użytkownicy dodają i potwierdzają wybiegi, a automatyczna moderacja
(z Claude) pilnuje, żeby nie trafiło tam byle co. Bez reklam i kont. Lokalizacja
nie opuszcza telefonu.

| | |
|---|---|
| Nazwa w sklepie | Psie Parki |
| `applicationId` | `com.adminstack.psieparki` (**nieodwracalny** po pierwszej publikacji) |
| Wersja | 1.0.0 (versionCode 1), niewydana |
| Dane | OpenStreetMap (`leisure=dog_park`), co tydzień + zgłoszenia z aplikacji, co 30 min + ręczne poprawki |
| Zgłoszenia | Cloudflare Worker (`worker/`) → issue → `moderacja.yml`; opis i uruchomienie: **[MODERACJA.md](MODERACJA.md)** |
| Mapa | MapLibre + kafelki OpenFreeMap (bez klucza API, bez limitów) |

## Jak to jest poskładane

```
scraper/build.py          OSM → data/osm-parks.json → + zgłoszenia + poprawki → data/parks.json
scraper/zgloszenia.py     reguły zgłoszeń: duplikaty, potwierdzenia od innych osób, głosy na cechy
scraper/moderuj.py        workflow moderacji: issues → decyzje → dane → komentarze
scraper/pbf.py            to samo z pliku .osm.pbf, gdy Overpass leży
scraper/normalize.py      tagi OSM → cechy wybiegu (tak / nie / brak danych)
scraper/manual.py         nakłada manual/poprawki.json
scraper/CONTRACT.md       format data/parks.json — wiążący dla apki
manual/poprawki.json      ręczne poprawki, ukryte i dodane wybiegi (edytuje człowiek, wygrywa)
manual/zgloszenia.json    stan zgłoszeń z aplikacji (pisze tylko workflow moderacji)
worker/                   Cloudflare Worker: przyjmuje zgłoszenia, limity, moderacja tekstu (Claude)
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

CI: `.github/workflows/data.yml` co poniedziałek przebudowuje dane z OSM (Overpass, a gdy
padnie — wycinek Polski z Geofabrik) i commituje tylko, gdy zmienił się jakiś wybieg.
`moderacja.yml` co 30 minut rozpatruje zgłoszenia z aplikacji. `app.yml` buduje i testuje
apkę i workera przy każdym PR.

Worker: `cd worker && npm ci && npm run check && npm test`. Bez wdrożonego workera
(`-PzgloszeniaUrl` niepodane) aplikacja dodaje wybiegi mailem, jak w 1.0.

### Emulator

W chmurowym kontenerze nie ma KVM, ale emulator działa w trybie programowym — wolno
(start ~12 min, każdy ekran kilkadziesiąt sekund), na tyle, żeby przejść główne ścieżki:

```bash
sdkmanager "emulator" "system-images;android-30;default;x86_64"
avdmanager create avd -n pp30 -k "system-images;android-30;default;x86_64" -d pixel_4
emulator -avd pp30 -no-window -no-audio -no-snapshot -accel off -gpu swiftshader_indirect &
adb shell settings put global hide_error_dialogs 1      # ANR-y od samej powolności
adb emu geo fix 18.5418 50.0971                         # rynek w Rybniku
adb reverse tcp:8787 tcp:8787                           # emulator nie ma tu sieci; worker na hoście
./gradlew installDebug -PzgloszeniaUrl=http://localhost:8787
```

## Zgłoszenia od użytkowników

Dodawanie, potwierdzanie i opisywanie wybiegów idzie przez aplikację i automatyczną
moderację — bez Twojego udziału ([MODERACJA.md](MODERACJA.md)). Mailem przychodzi tylko
„Zgłoś poprawkę” (dowolny opis błędu, z id wybiegu i współrzędnymi). Co z nim zrobić:

1. **Fakt o miejscu** (jest ogrodzenie, wybieg zlikwidowany, brakujący wybieg) — najlepiej
   poprawić w OpenStreetMap (link „Zobacz lub popraw w OpenStreetMap” w apce). Trafi do
   apki przy najbliższej aktualizacji i do każdej innej mapy.
2. **Coś, czego OSM nie przechowa** (dni adopcji, regulamin) albo pilna poprawka przed
   aktualizacją OSM — wpis w `manual/poprawki.json` (instrukcja w pliku), commit, push.

## Rzeczy, o których trzeba pamiętać

- **Danych jest mało.** W październiku 2026 OSM miał w Polsce 618 publicznych wybiegów:
  sporo w dużych miastach (Warszawa 46, Gdańsk 43, Kraków 40), prawie nic na wsi. Cechy
  są opisane rzadko (ogrodzenie: 262, woda: 0). Dlatego apka mówi „brak danych”, a nie
  „nie”, i dlatego prosi o pomoc tam, gdzie ludzie już są: „Byłeś tu?” na stronie
  wybiegu i „W promieniu 5 km nie znamy żadnego wybiegu” na liście. Inne źródła są
  słabsze: psiPARK.pl miał ok. 275 obiektów (2023, treść chroniona prawem autorskim),
  lista Warszawy 19115 to DOCX bez współrzędnych z ok. 20 wybiegami.
- **Mapa: MapLibre w wersji OpenGL** (`android-sdk-opengl`). Domyślny artefakt renderuje
  Vulkanem i na emulatorze Androida 11 wywracał aplikację (SIGSEGV) przy pierwszej
  klatce mapy. Bez internetu i bez pamięci podręcznej mapa przechodzi na styl lokalny:
  samo tło i pinezki.
- **Kafelki mapy z OpenFreeMap**, nie z tile.openstreetmap.org — zasady OSMF zabraniają
  używania ich serwera kafelków w aplikacjach. Atrybucja (OSM, OpenMapTiles) jest na mapie
  i w „Więcej”; nie usuwaj jej, licencja ODbL tego wymaga.
- **Dane muszą być publiczne** pod `ParksRepository.DATA_URL` — raw.githubusercontent.com
  nie poda pliku z prywatnego repo.
- **Keystore**: `keystore.properties` poza repo (jest w `.gitignore`), Play App Signing
  przy pierwszym wydaniu. Szczegóły w `play/PUBLISHING.md`.
