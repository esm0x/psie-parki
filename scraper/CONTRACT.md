# Kontrakt danych — scraper ↔ aplikacja

`scraper/build.py` zapisuje `data/parks.json`. Aplikacja pobiera go spod
`https://raw.githubusercontent.com/esm0x/psie-parki/main/data/parks.json` i deserializuje
1:1 do `Park` w `app/.../data/Park.kt`. Przed zapisem `scraper/contract.py` sprawdza plik
z tym dokumentem — plik łamiący kontrakt nie zostaje zapisany, w repo zostaje poprzedni.
**Nazwy pól są wiążące**: literówka = puste pole w apce. Nowe pola wolno dodawać (apka
ignoruje nieznane), istniejących nie wolno zmieniać ani usuwać.

```json
{
  "generated_at": "2026-10-10T19:09:41",
  "osm_base": "2026-10-09T01:39:36Z",
  "count": 618,
  "failures": [],
  "parks": [
    {"id":"node/5655915525","name":"Górka dla Burka","lat":50.102656,"lon":18.515964,
     "locality":"Rybnik","district":"Maroko-Nowiny","region":"śląskie","areaM2":null,
     "fenced":true,"lit":null,"water":null,"agility":true,"smallDogArea":null,
     "benches":null,"bins":null,"surface":null,"openingHours":null,"operator":null,
     "website":null,"description":null,"note":"Ogrodzony, z dwiema śluzami…",
     "customersOnly":false,"source":"osm"}
  ]
}
```

| Pole | Typ | Znaczenie |
|---|---|---|
| `id` | string | `node/…`, `way/…`, `relation/…` (jak w adresie osm.org) albo `manual/<slug>` |
| `name` | string \| null | nazwa własna; ogólniki („Wybieg dla psów”) są usuwane |
| `lat`, `lon` | number | środek wybiegu, 6 miejsc po przecinku, w granicach Polski |
| `locality` | string \| null | miejscowość (granica OSM poziomu 8, a gdy jej brak — gmina bez przedrostka „gmina”) |
| `district` | string \| null | dzielnica (poziom 9, a gdy brak — osiedle, poziom 10) |
| `region` | string \| null | województwo bez słowa „województwo” |
| `areaM2` | int \| null | powierzchnia z obrysu, dla punktów `null` |
| `fenced`, `lit`, `water`, `agility`, `smallDogArea`, `benches`, `bins` | bool \| null | **`null` = nikt nie opisał**, nie „nie” |
| `surface` | string \| null | po polsku: „trawa”, „piasek, żwir” |
| `openingHours` | string \| null | `opening_hours` z OSM; `24/7` → „całą dobę” |
| `operator`, `website`, `description` | string \| null | z OSM; `website` zawsze `http(s)://` |
| `note` | string \| null | tylko z `manual/poprawki.json`; apka pokazuje go w ramce |
| `customersOnly` | bool | `access=customers` (wybieg hotelu, sklepu, szkoły dla psów) |
| `source` | `"osm"` \| `"manual"` | skąd wpis |

Wybiegi z `access=private` albo `access=no` są pomijane. `failures` to lista
`{"source", "error"}` — nigdy nie blokuje zapisu (np. poprawka do wybiegu, którego już
nie ma w OSM). Mniej niż 400 wybiegów w całym kraju oznacza uciętą odpowiedź Overpass
i plik **nie** jest zapisywany.

## Skąd cechy (tagi OSM)

| Cecha | Tagi, pierwszy pasujący wygrywa |
|---|---|
| `fenced` | `fenced`, `fence`, `dog_park:fenced`, a potem `barrier=fence/wall/…` |
| `lit` | `lit` (`24/7`, `sunset-sunrise` = tak) |
| `water` | `drinking_water`, `dog_park:drinking_water`, `water_point` |
| `agility` | `dog_park:agility`, `agility`, `dog_agility`, `playground:dog` |
| `smallDogArea` | `dog_park:small_dogs`, `small_dog`, `dog_park:separate_small_dogs` |
| `benches` | `bench`, `benches` |
| `bins` | `waste_basket`, `dog_park:waste_basket`, `vending:excrement_bags` |
