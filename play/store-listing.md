# Karta w Google Play — Psie Parki

Teksty do wklejenia w Play Console → **Rozwój → Obecność w sklepie → Główna strona aplikacji**.
Do wklejania bierz pliki `.txt` — w nich akapity nie są łamane.

## Nazwa aplikacji (max 30 znaków)

```
Psie Parki
```

10 znaków. Zgadza się z `app_name` w `app/src/main/res/values/strings.xml`.

## Krótki opis (max 80 znaków) — `opis-krotki.txt`

```
Wybiegi dla psów w całej Polsce: najbliższe, na mapie. Dodaj swój. Bez reklam.
```

78 znaków.

## Pełny opis (max 4000 znaków) — `opis-pelny.txt`

2352 znaków. Zawiera zdanie o niezależności od urzędów i atrybucję OpenStreetMap
(licencja ODbL jej wymaga) — **nie usuwaj ich**.

## Kategoria i kontakt

- **Kategoria:** Podróże i informacje lokalne (alternatywa: Styl życia)
- **Tagi:** mapy, informacje lokalne, zwierzęta
- **E-mail:** office@admin-stack.com
- **Polityka prywatności:** `https://github.com/esm0x/psie-parki/blob/main/PRIVACY.md`

## Co nowego (pierwsze wydanie, max 500 znaków)

```
Pierwsze wydanie. Wybiegi dla psów w całej Polsce: lista od najbliższego, mapa,
szczegóły (ogrodzenie, oświetlenie, woda, agility), filtry, wybór miejscowości bez
udostępniania lokalizacji. Dodawanie i potwierdzanie wybiegów. Jasny i ciemny motyw.
```

## Grafiki

- `graphics/icon-512.png` — ikona 512 × 512, bez przezroczystości i bez własnej ramki
  (Play sam zaokrągla rogi).
- `graphics/feature-graphic-1024x500.png` — grafika promocyjna.

## Zrzuty ekranu

Wszystkie 9:16, podpis w górnym pasie zajmuje 18,75% wysokości (Google pozwala na 20%),
bez ramek urządzeń i haseł reklamowych.

**Telefon** — `screenshots/phone/`, 1080 × 1920, w tej kolejności (pierwsze trzy widać
w wynikach wyszukiwania):

1. `01-najblizsze.png` — Najbliższe wybiegi dla psa
2. `02-mapa.png` — Wszystkie wybiegi na mapie
3. `03-szczegoly.png` — Co jest na wybiegu
4. `04-filtry.png` — Tylko ogrodzone?
5. `05-miejscowosci.png` — Bez udostępniania lokalizacji
6. `06-dodaj-wybieg.png` — Znasz wybieg? Dodaj go
7. `07-ciemny-motyw.png` — Jasny i ciemny motyw

**Tablet 7"** — `screenshots/tablet-7/`, 1080 × 1920. **Tablet 10"** — `screenshots/tablet-10/`,
1440 × 2560. Po cztery: najbliższe, mapa, szczegóły, miejscowości.

### Skąd są zrzuty i jak je odświeżyć

Zrzuty to prawdziwe ekrany aplikacji z prawdziwymi danymi (`data/parks.json`),
renderowane przez Robolectric bez emulatora (`app/src/test/.../StoreScreenshots.kt`),
z punktu odniesienia na rynku w Rybniku. Jedyny wyjątek: na zrzucie mapy sam
podkład z pinezkami narysował MapLibre GL JS (`map/render.mjs`) z tym samym stylem,
danymi i kolorami co aplikacja, bo mapy OpenGL nie da się uruchomić w Robolectric.
Karta wybiegu, przyciski i reszta ekranu są z aplikacji.

```bash
NODE_PATH=$(npm root -g) node play/map/render.mjs
./gradlew testDebugUnitTest -Pscreenshots --tests '*StoreScreenshots*'
python play/make_store_assets.py
```

Przed wydaniem warto zrobić jeden zrzut mapy na prawdziwym telefonie i porównać.
