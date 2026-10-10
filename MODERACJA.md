# Zgłoszenia z aplikacji i ich moderacja

Aplikacja pozwala dodać wybieg, potwierdzić go, zgłosić, że go nie ma, i opisać cechy
(ogrodzenie, światło, woda…). Wszystko działa **bez obsługi**: nikt nie musi czytać
zgłoszeń, a mimo to nie da się wrzucić na mapę czegokolwiek.

```
aplikacja ──POST──▶ worker (Cloudflare)        ──issue──▶ GitHub  ──co 30 min──▶ moderacja.yml
                     • walidacja, limity                             • duplikaty, potwierdzenia
                     • Claude: nazwa i uwagi                         • kontekst OSM (woda? budynek?)
                       (spam, reklama, wulgaryzmy,                   • manual/zgloszenia.json
                        dane osobowe) → odmowa od razu               • data/parks.json, komentarz, zamknięcie
aplikacja ◀─GET /zgloszenie/<nr>── status z etykiet issue
```

## Warstwy zabezpieczeń

| Co | Gdzie | Przed czym chroni |
|---|---|---|
| Walidacja: typy, długości, Polska, brak linków/e-maili/telefonów | worker, `src/submission.ts` | śmieci, spam z linkami — zanim cokolwiek kosztuje |
| Limity dzienne: 10 na instalację, 30 na sieć, 500 łącznie | worker, KV | zalew zgłoszeń, skrypty |
| **Claude** ocenia nazwę i uwagi | worker, `src/moderate.ts` | reklama, wulgaryzmy, dane osobowe, prompt injection. Odrzucona treść **nie trafia do publicznego repo**, a autor od razu widzi dlaczego |
| Anonimizacja: HMAC instalacji (`autor`) i sieci /24 (`siec`) | worker | w repo nie ma ID urządzeń ani adresów IP |
| Tylko issues od konta workera z etykietą `zgloszenie` | `moderacja.yml`, `moderuj.py` | obca osoba nie wstrzyknie zgłoszenia przez zwykłe issue |
| Punkt w wodzie, budynku, na torach, przy autostradzie → odmowa | `moderuj.py` (Overpass) | pinezki postawione byle gdzie |
| Nowy wybieg ≤ 60 m od znanego = potwierdzenie tamtego | `zgloszenia.py` | duplikaty |
| Nowy wybieg jest „niepotwierdzony”, dopóki nie potwierdzi go **inna osoba** (inna instalacja *i* inna sieć) | `zgloszenia.py` | jedna osoba z dwoma telefonami w domu |
| Najwyżej 3 niepotwierdzone wybiegi na osobę | `zgloszenia.py` | zasypanie mapy przez jedną osobę |
| Niepotwierdzony znika po 1 niezależnym „nie ma go”; potwierdzony lub z OSM po 2, jeśli przeważają | `zgloszenia.py` | fałszywe wpisy; ale i trolle kasujące prawdziwe wybiegi |
| Cechy: puste pola z pierwszego głosu, nadpisanie OSM dopiero przy 2 zgodnych | `zgloszenia.py` | jedna osoba nie zmieni tego, co opisali mapujący |
| `manual/poprawki.json` ma pierwszeństwo | `build.py` | Ty zawsze możesz ukryć lub poprawić ręcznie |
| Każda decyzja = komentarz w issue + commit | GitHub | pełny ślad, revert jednym commitem |

Koszt AI: tylko zgłoszenia z tekstem (nazwa lub uwagi), jedno krótkie zapytanie do
`claude-opus-5-5` z niskim `effort` — około 1 grosza na zgłoszenie. Kliknięcia
„Wybieg jest” / „Tak / Nie” nie wołają modelu wcale. Żeby zejść z kosztem dalej,
zmień `MODEL` w `worker/src/moderate.ts` na `claude-haiku-5-5`.

## Uruchomienie (jednorazowo, ok. 20 minut)

### 1. Konto GitHub dla workera

Najprościej: osobne konto (np. `psie-parki-bot`) dodane do repo jako współpracownik
z rolą **Triage** — może zakładać i etykietować issues, nie może pushować.
Na tym koncie: *Settings → Developer settings → Fine-grained tokens* → token tylko do
`esm0x/psie-parki`, uprawnienie **Issues: Read and write**. To jest `GITHUB_TOKEN` workera.

W repo: *Settings → Secrets and variables → Actions → Variables* →
`ZGLOSZENIA_BOT` = login tego konta. Workflow ignoruje issues od kogokolwiek innego.

Utwórz etykiety: `zgloszenie`, `typ:nowy`, `typ:cechy`, `typ:potwierdzam`,
`typ:nie_istnieje`, `przyjete`, `niepotwierdzone`, `odrzucone`
(etykiety `powod:*` workflow tworzy sam przy pierwszym użyciu).

### 2. Klucz Anthropic

https://console.anthropic.com → API Keys → nowy klucz, najlepiej w osobnym workspace
z limitem wydatków (np. 10 USD/mies.).

### 3. Worker na Cloudflare (darmowy plan wystarczy)

```bash
cd worker
npm install
npx wrangler login
npx wrangler kv namespace create LIMITS      # id wklej do wrangler.toml
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler secret put GITHUB_TOKEN
npx wrangler secret put HASH_SECRET           # dowolny długi losowy ciąg; nie zmieniaj go potem
npx wrangler deploy                           # wypisze adres, np. https://psie-parki-zgloszenia.<konto>.workers.dev
```

### 4. Aplikacja

Adres workera wchodzi do buildu przez właściwość Gradle (nie jest w repo):

```properties
# ~/.gradle/gradle.properties
zgloszeniaUrl=https://psie-parki-zgloszenia.<konto>.workers.dev
```

Build bez tej właściwości działa jak wersja 1.0: „Dodaj wybieg” otwiera gotowego maila.

### 5. Sprawdzenie

```bash
curl -s -X POST "$URL/zgloszenie" -H 'content-type: application/json' \
  -d '{"typ":"potwierdzam","parkId":"node/5655915525","instalacja":"3f1c2b4a-8d7e-4f6a-9b0c-1d2e3f4a5b6c","wersja":"test"}'
# → {"nr": 12, "status": "oczekuje"}; w repo pojawia się issue, po ≤30 min komentarz i zamknięcie
```

## Na co uważać

- **Zgłoszenia są publiczne** (issues w publicznym repo): współrzędne, nazwa, uwagi, skróty
  autora. Dlatego tekst przechodzi przez model *przed* założeniem issue, a aplikacja mówi
  o tym przy formularzu. Polityka prywatności to opisuje.
- **Push z workflow do `main`** wymaga, żeby gałąź nie była chroniona przed GitHub Actions.
- **Zdjęcia** celowo nie są obsługiwane: moderacja obrazów i hosting to osobny temat.
- **Do OSM nic nie trafia automatycznie** — import danych do OSM wymaga zgody społeczności.
  Potwierdzone wybiegi z aplikacji warto co jakiś czas wprowadzić tam ręcznie.
- **Play Integrity** (sprawdzanie, że zapytanie pochodzi z prawdziwej aplikacji ze sklepu)
  to następny krok, gdyby pojawił się spam ze skryptów omijających limity.
