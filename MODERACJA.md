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
| Tylko issues od konta z `ZGLOSZENIA_BOT` z etykietą `zgloszenie`, JSON z ostatniego bloku | `moderacja.yml`, `moderuj.py` | obca osoba nie wstrzyknie zgłoszenia |
| Punkt w wodzie, budynku, na torach, przy autostradzie → odmowa | `moderuj.py` (Overpass) | pinezki postawione byle gdzie |
| Nowy wybieg ≤ 60 m od znanego = potwierdzenie tamtego | `zgloszenia.py` | duplikaty |
| Niezależność liczy się po **sieci**, nie instalacji; potwierdzenie musi przyjść innego dnia | `zgloszenia.py` | jedna osoba z kilkoma telefonami albo reinstalacją |
| Najwyżej 3 oczekujące wybiegi na sieć, 2 nowe dziennie na sieć, 20 dziennie w kraju | `zgloszenia.py` | zasypanie mapy |
| Niepotwierdzony wybieg znika po 90 dniach | `zgloszenia.py` | martwe wpisy |
| Wybieg z OSM chowa się dopiero po „nie ma go” z 3 sieci w 2 różne dni, najwyżej 3 dziennie w kraju; wraca, gdy potwierdzeń jest więcej | `zgloszenia.py` | trolle kasujące prawdziwe wybiegi |
| Cechy: puste pola z pierwszego głosu, nadpisanie OSM dopiero przy 2 zgodnych sieciach | `zgloszenia.py` | jedna osoba nie zmieni tego, co opisali mapujący |
| `manual/poprawki.json` ma pierwszeństwo | `build.py` | Ty zawsze możesz ukryć lub poprawić ręcznie |
| Każda decyzja = komentarz w issue + commit | GitHub | pełny ślad, revert jednym commitem |

## Uruchomienie (jednorazowo, ok. 30 minut)

Potrzebujesz: konta GitHub z dostępem do repo, darmowego konta Cloudflare i komputera
z Node.js 20+ (`node -v`). Dopóki nie skończysz kroku 2, workflow moderacji śpi.

### 1. Token GitHub dla workera

Worker zakłada issues **z Twojego konta**, tokenem, który umie tylko to. Osobne konto-bot
z rolą Triage nie zadziała: GitHub po cichu zdejmuje etykiety z nowych issues, jeśli
autor nie ma prawa zapisu, i moderacja nigdy by ich nie zobaczyła.

1. https://github.com/settings/personal-access-tokens/new (*Fine-grained token*).
2. **Token name:** `psie-parki-worker`. **Expiration:** najdłuższa możliwa; wpisz sobie
   przypomnienie, bo po wygaśnięciu aplikacja zacznie pokazywać „nie udało się wysłać”.
3. **Repository access → Only select repositories →** `esm0x/psie-parki`.
4. **Permissions → Repository permissions → Issues: Read and write.** Nic więcej
   (Metadata: Read-only dopisze się sama).
5. **Generate token**, skopiuj `github_pat_…` — przyda się w kroku 3.

### 2. Włączenie moderacji w repo

1. Repo → *Settings → Secrets and variables → Actions →* zakładka **Variables** →
   *New repository variable*: `ZGLOSZENIA_BOT` = `esm0x` (login właściciela tokenu).
   Workflow bierze pod uwagę tylko issues od tego konta.
2. Repo → *Actions → Moderacja zgłoszeń → Run workflow*. Pierwszy przebieg zakłada
   etykiety (`zgloszenie`, `typ:*`, `przyjete`, `niepotwierdzone`, `odrzucone`) i kończy
   się na zielono. Od teraz rusza co 30 minut i przy każdym nowym zgłoszeniu.
3. Nie włączaj ochrony gałęzi `main`, która blokuje push z GitHub Actions — workflow
   commituje tam decyzje.

Wbudowany `GITHUB_TOKEN` workflow wystarcza do reszty; żadnych innych kluczy nie ma.

### 3. Worker na Cloudflare

Konto: https://dash.cloudflare.com/sign-up (plan Free, karta niepotrzebna). Potem
w terminalu, w katalogu repo:

```bash
cd worker
npm ci
npx wrangler login                      # otworzy przeglądarkę, kliknij Allow
npx wrangler kv namespace create LIMITS
```

Ostatnie polecenie wypisze `id = "…"`. Wklej je w `worker/wrangler.toml` w miejsce
`UZUPELNIJ_PO_UTWORZENIU` (i zacommituj — to nie jest tajne).

```bash
npx wrangler secret put GITHUB_TOKEN    # wklej github_pat_… z kroku 1
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
npx wrangler secret put HASH_SECRET     # wklej wynik polecenia wyżej; nigdy go nie zmieniaj
npx wrangler deploy
```

Przy pierwszym `deploy` Cloudflare może poprosić o wybór subdomeny `*.workers.dev`.
Na końcu wypisze adres, np. `https://psie-parki-zgloszenia.<subdomena>.workers.dev` —
to jest `URL` z kroków 4 i 5. `HASH_SECRET` zmieniony później rozdzieliłby te same osoby
na nowe, więc limity i potwierdzenia liczyłyby je podwójnie.

### 4. Sprawdzenie

```bash
URL=https://psie-parki-zgloszenia.<subdomena>.workers.dev
curl -s -X POST "$URL/zgloszenie" -H 'content-type: application/json' \
  -d '{"typ":"potwierdzam","parkId":"node/5655915525","instalacja":"3f1c2b4a-8d7e-4f6a-9b0c-1d2e3f4a5b6c","wersja":"test"}'
# → {"nr":12,"token":"…","status":"oczekuje"}
```

W repo pojawi się issue `[Potwierdzenie] node/5655915525` z etykietą `zgloszenie`, a po paru minutach
komentarz moderacji i zamknięcie. Status, tak jak pyta aplikacja:

```bash
curl -s "$URL/zgloszenie/12?t=<token z odpowiedzi>"   # → {"nr":12,"status":"przyjete",…}
```

To prawdziwe potwierdzenie Górki dla Burka — nieszkodliwe. Gdy coś nie działa:
`npx wrangler tail` pokazuje na żywo, co dzieje się w workerze, a zakładka *Actions*
w repo — logi moderacji.

### 5. Aplikacja

Adres workera wchodzi do buildu przez właściwość Gradle (nie ma go w repo):

```properties
# ~/.gradle/gradle.properties
zgloszeniaUrl=https://psie-parki-zgloszenia.<subdomena>.workers.dev
```

Potem `./gradlew bundleRelease` (podpis: `play/PUBLISHING.md`). Build bez tej
właściwości działa jak wersja 1.0: „Dodaj wybieg” otwiera gotowego maila.

### Utrzymanie

- **Token wygasł** → nowy jak w kroku 1, potem `npx wrangler secret put GITHUB_TOKEN`.
- **Zmiana kodu workera** → `cd worker && npm test && npx wrangler deploy`.
- **Coś się prześlizgnęło** → dopisz przypadek w `worker/test/filter.test.ts`, popraw
  `src/filter.ts`, deploy; sam wybieg schowaj w `manual/poprawki.json`.
- **Powiadomienia**: issues zakłada Twoje konto, więc GitHub może przysyłać maile
  o komentarzach moderacji. Wyłączysz to w repo: *Watch → Custom → bez Issues*.

## Na co uważać

- **Zgłoszenia są publiczne** (issues w publicznym repo): współrzędne, nazwa, cechy, skróty
  autora. Dlatego nazwa przechodzi przez filtr *przed* założeniem issue, tytuł issue nie
  zawiera nazwy, a aplikacja mówi o tym przy formularzu. Polityka prywatności to opisuje.
- **Żaden filtr słów nie jest szczelny.** Kto się uprze, wymyśli obejście; dlatego nazwa
  nowego wybiegu pokazuje się jako „niepotwierdzona”, dopóki ktoś z innej sieci go nie
  potwierdzi, a każdą nazwę możesz nadpisać w `manual/poprawki.json`.
- **Nazwy z OSM nie przechodzą przez filtr** — moderują je mapujący OSM.
- **Zdjęcia** celowo nie są obsługiwane: moderacja obrazów i hosting to osobny temat.
- **Do OSM nic nie trafia automatycznie** — import danych do OSM wymaga zgody społeczności.
  Potwierdzone wybiegi z aplikacji warto co jakiś czas wprowadzić tam ręcznie.
- **Play Integrity** (sprawdzanie, że zapytanie pochodzi z prawdziwej aplikacji ze sklepu)
  to następny krok, gdyby pojawił się spam ze skryptów omijających limity.
