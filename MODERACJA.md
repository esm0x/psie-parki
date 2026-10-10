# Zgłoszenia z aplikacji i ich moderacja

Aplikacja pozwala dodać wybieg, potwierdzić go, zgłosić, że go nie ma, i opisać cechy
(ogrodzenie, światło, woda…). Wszystko działa **bez obsługi**: nikt nie musi czytać
zgłoszeń, a mimo to nie da się wrzucić na mapę czegokolwiek.

```
aplikacja ──POST──▶ worker (Cloudflare, darmowy)   ──issue──▶ GitHub  ──co 30 min──▶ moderacja.yml
                     • walidacja, limity                                • duplikaty, potwierdzenia
                     • filtr nazw: wulgaryzmy,                          • kontekst OSM (woda? budynek?)
                       reklama, kontakty, bełkot                        • manual/zgloszenia.json
                       → odmowa od razu                                 • data/parks.json, komentarz, zamknięcie
aplikacja ◀─GET /zgloszenie/<nr>?t=…── status z etykiet issue (cache 10 min)
```

**Bez AI, bez opłat, bez Twojego czasu.** Wszystko to zwykły kod: filtr słów i reguły
liczące niezależne potwierdzenia. Koszt: 0 zł (darmowy plan Cloudflare Workers i GitHub
Actions w publicznym repo). Nic nie czeka na człowieka.

## Dlaczego jest worker, a nie „sam algorytm w aplikacji”

Aplikacja nie ma kont i nie może trzymać w sobie tokenu do GitHuba — każdy wyciągnąłby
go z APK. Coś musi przyjąć zgłoszenie od anonimowej osoby i założyć issue własnym
tokenem. Worker to ok. 150 linii TypeScriptu bez zależności, na darmowym planie
(100 000 zapytań dziennie). Alternatywy rozważone i odrzucone:

- **Formularz Google** — też darmowy, ale odpowiedzi wpadają do arkusza, który ktoś
  musiałby przenosić do repo, nie ma limitów na osobę ani filtra przed zapisem.
- **Issue zakładane przez użytkownika** — wymaga konta GitHub; nikt go nie ma.
- **Własny serwer / Firebase** — coś do utrzymania albo płatne powyżej limitów.

## Filtr nazw (`worker/src/filter.ts`)

Jedyny tekst, jaki użytkownik wpisuje, to nazwa wybiegu. Pole „uwagi” usunięte: wolny
tekst trudno sprawdzić bez AI, a mapie nic nie dawał. Filtr najpierw sprowadza tekst do prostej
postaci, a dopiero potem szuka:

- **ujednolicenie**: NFKC, usunięte niewidoczne znaki, cyrylica i greka udające łacinę
  (czytane i tak, jak wyglądają, i tak, jak brzmią: `курwa`), leet (`kurw4`, `ku®wa`),
  polskie znaki, powtórzone litery (`kuuurwa`), litery rozstrzelone (`k u r w a`, `k.u.r.w.a`), `v`→`w`/`u` (`kurvva`, `fvck`);
- **wulgaryzmy** PL i EN po rdzeniach, część z kotwicą na początek słowa, żeby nie
  odrzucać prawdziwych nazw — „Skurwiel” odpada, „Park Nigeryjski” przechodzi;
- **reklama i kontakty**: adresy WWW, e-maile (też `kropka`/`małpa`), numery telefonów
  (7+ cyfr), tablice rejestracyjne, słowa typu „sklep”, „promocja”, „groomer”, „kup”;
- **bełkot**: `xd`, `lol`, `asdf`, same cyfry, ten sam znak w kółko, znaki `<>{}@#$…`;
- **porządek**: nazwy w stylu „Wybieg dla psów” są zamieniane na brak nazwy (apka pokaże
  okolicę), CAPS LOCK na zwykłe litery.

Odrzucona nazwa **nie trafia do publicznego repo**, a aplikacja od razu mówi, co poprawić.
Testy: `worker/test/filter.test.ts` — lista nazw, które muszą przejść, i takich, które
muszą odpaść; dopisz tam każdy przypadek, który się prześlizgnie.

## Warstwy zabezpieczeń

| Co | Gdzie | Przed czym chroni |
|---|---|---|
| Walidacja: typy, długości, Polska, wersja aplikacji z białej listy, znaki sterujące i „zalgo” wycięte | worker, `src/submission.ts` | śmieci, zanim cokolwiek się zapisze |
| Filtr nazw (wyżej) | worker, `src/filter.ts` | wulgaryzmy, reklama, dane kontaktowe, bełkot |
| Seria: 5 zgłoszeń na minutę z jednej sieci | worker, Rate Limiting Cloudflare | skrypty |
| Dziennie: 10 na instalację, 20 na sieć (IPv4 /24, IPv6 /64) | worker, KV | zalew zgłoszeń |
| Anonimizacja: HMAC instalacji (`autor`) i sieci (`siec`) | worker | w repo nie ma ID urządzeń ani adresów IP |
| Status zgłoszenia tylko z tokenem z odpowiedzi, z cache | worker | wyliczanie cudzych zgłoszeń, zużycie limitu API |
| Tylko issues od konta workera z etykietą `zgloszenie`, JSON z ostatniego bloku | `moderacja.yml`, `moderuj.py` | obca osoba nie wstrzyknie zgłoszenia |
| Punkt w wodzie, budynku, na torach, przy autostradzie → odmowa | `moderuj.py` (Overpass) | pinezki postawione byle gdzie |
| Nowy wybieg ≤ 60 m od znanego = potwierdzenie tamtego | `zgloszenia.py` | duplikaty |
| Niezależność liczy się po **sieci**, nie instalacji; potwierdzenie musi przyjść innego dnia | `zgloszenia.py` | jedna osoba z kilkoma telefonami albo reinstalacją |
| Najwyżej 3 oczekujące wybiegi na sieć, 2 nowe dziennie na sieć, 20 dziennie w kraju | `zgloszenia.py` | zasypanie mapy |
| Niepotwierdzony wybieg znika po 90 dniach | `zgloszenia.py` | martwe wpisy |
| Wybieg z OSM chowa się dopiero po „nie ma go” z 3 sieci w 2 różne dni, najwyżej 3 dziennie w kraju; wraca, gdy potwierdzeń jest więcej | `zgloszenia.py` | trolle kasujące prawdziwe wybiegi |
| Cechy: puste pola z pierwszego głosu, nadpisanie OSM dopiero przy 2 zgodnych sieciach | `zgloszenia.py` | jedna osoba nie zmieni tego, co opisali mapujący |
| `manual/poprawki.json` ma pierwszeństwo | `build.py` | Ty zawsze możesz ukryć lub poprawić ręcznie |
| Każda decyzja = komentarz w issue + commit | GitHub | pełny ślad, revert jednym commitem |

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

GITHUB_TOKEN workflow (wbudowany) wystarcza do reszty; żadnych innych kluczy nie ma.

### 2. Worker na Cloudflare (darmowy plan wystarczy)

```bash
cd worker
npm install
npx wrangler login
npx wrangler kv namespace create LIMITS      # id wklej do wrangler.toml
npx wrangler secret put GITHUB_TOKEN
npx wrangler secret put HASH_SECRET           # dowolny długi losowy ciąg; nie zmieniaj go potem
npx wrangler deploy                           # wypisze adres, np. https://psie-parki-zgloszenia.<konto>.workers.dev
```

### 3. Aplikacja

Adres workera wchodzi do buildu przez właściwość Gradle (nie jest w repo):

```properties
# ~/.gradle/gradle.properties
zgloszeniaUrl=https://psie-parki-zgloszenia.<konto>.workers.dev
```

Build bez tej właściwości działa jak wersja 1.0: „Dodaj wybieg” otwiera gotowego maila.

### 4. Sprawdzenie

```bash
curl -s -X POST "$URL/zgloszenie" -H 'content-type: application/json' \
  -d '{"typ":"potwierdzam","parkId":"node/5655915525","instalacja":"3f1c2b4a-8d7e-4f6a-9b0c-1d2e3f4a5b6c","wersja":"test"}'
# → {"nr": 12, "token": "…", "status": "oczekuje"}; w repo pojawia się issue, po ≤30 min komentarz i zamknięcie
```

## Na co uważać

- **Zgłoszenia są publiczne** (issues w publicznym repo): współrzędne, nazwa, cechy, skróty
  autora. Dlatego nazwa przechodzi przez filtr *przed* założeniem issue, tytuł issue nie
  zawiera nazwy, a aplikacja mówi o tym przy formularzu. Polityka prywatności to opisuje.
- **Żaden filtr słów nie jest szczelny.** Kto się uprze, wymyśli obejście; dlatego nazwa
  nowego wybiegu pokazuje się jako „niepotwierdzona”, dopóki ktoś z innej sieci go nie
  potwierdzi, a każdą nazwę możesz nadpisać w `manual/poprawki.json`.
- **Nazwy z OSM nie przechodzą przez filtr** — moderują je mapujący OSM.
- **Push z workflow do `main`** wymaga, żeby gałąź nie była chroniona przed GitHub Actions.
- **Zdjęcia** celowo nie są obsługiwane: moderacja obrazów i hosting to osobny temat.
- **Do OSM nic nie trafia automatycznie** — import danych do OSM wymaga zgody społeczności.
  Potwierdzone wybiegi z aplikacji warto co jakiś czas wprowadzić tam ręcznie.
- **Play Integrity** (sprawdzanie, że zapytanie pochodzi z prawdziwej aplikacji ze sklepu)
  to następny krok, gdyby pojawił się spam ze skryptów omijających limity.
