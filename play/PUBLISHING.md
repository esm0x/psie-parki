# Publikacja w Google Play — Psie Parki

Wszystko, co dało się przygotować z repozytorium, jest gotowe: konfiguracja
podpisywania, R8, ikona, grafika promocyjna, zrzuty, opisy i polityka prywatności.
Konto Play Console, keystore i kolejne kroki są te same co przy Mój Rybnik —
szczegółowa instrukcja (pułapki z PKCS12, `jarsigner`, `cmd.exe`) jest
w `miasto-engine/cities/rybnik/play/PUBLISHING.md`. Poniżej tylko to, co tu jest inne.

## 1. Nieodwracalne

- **`applicationId` = `com.adminstack.psieparki`.** Po pierwszym uploadzie nie do zmiany.
- **Bezpłatna** — z darmowej na płatną nie da się przejść później.

## 2. Klucz i build

Ten sam keystore co dla Rybnika może podpisywać tę apkę (ten sam plik `.jks`; inny alias też jest OK).
`keystore.properties` w katalogu głównym (jest w `.gitignore`):

```properties
storeFile=C:/ŚCIEŻKA/DO/rybnik-release.jks
storePassword=TWOJE_HASLO
keyAlias=rybnik
keyPassword=TWOJE_HASLO
```

```bash
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab
```

Włącz **Play App Signing** przy pierwszym wydaniu.

## 3. Ustawienia aplikacji w Play Console

| Sekcja | Odpowiedź |
|---|---|
| Polityka prywatności | `https://github.com/esm0x/psie-parki/blob/main/PRIVACY.md` |
| Reklamy | Nie zawiera reklam |
| Dostęp do aplikacji | Cała zawartość dostępna bez ograniczeń |
| Ocena treści | „Narzędzia / informacje”, wszędzie „nie” → PEGI 3 |
| Grupa odbiorców | **13+**. Treści od użytkowników są moderowane, ale nie da się zagwarantować, że każda nadaje się dla dzieci. Nie zaznaczaj dzieci (włącza Families Policy) |
| Treści generowane przez użytkowników | **Tak** — zgłoszenia wybiegów. Moderacja automatyczna (`MODERACJA.md`), zgłaszanie nadużyć mailem |
| Aplikacja rządowa | Nie |

### Bezpieczeństwo danych

Od wersji z dodawaniem wybiegów aplikacja wysyła dane na nasz serwer (worker), więc
formularz **nie jest już „nic nie zbieramy”**. Odpowiedzi:

- **Zbiera dane?** → **Tak**:
  - *Aktywność w aplikacji → Inne treści generowane przez użytkownika*: dodane wybiegi,
    nazwy, uwagi, potwierdzenia. Cel: **Funkcje aplikacji**. **Opcjonalne.**
  - *Identyfikatory urządzenia lub inne → Identyfikator urządzenia lub inny*: losowy
    identyfikator instalacji (nie jest to ID reklamowe ani sprzętowe). Cel: **Funkcje
    aplikacji**, **Zapobieganie oszustwom i bezpieczeństwo**. **Opcjonalne.**
  - Przetwarzanie efemeryczne: **nie** (treść trafia do publicznego repozytorium).
- **Lokalizacja** → **nie zbierana**: położenie telefonu nie opuszcza urządzenia.
  Wysyłane jest miejsce wybiegu wskazane na mapie, a nie pozycja użytkownika.
- **Udostępnia dane?** → **Tak**: treści zgłoszeń są publikowane (GitHub) i oceniane przez
  Anthropic. Google traktuje przekazanie usługodawcy przetwarzającemu w naszym imieniu
  jako „nie udostępnianie”, ale publikacja w repozytorium to udostępnienie — zadeklaruj
  *Inne treści generowane przez użytkownika*.
- **Szyfrowanie w transmisji** → Tak (HTTPS).
- **Usuwanie danych** → Użytkownik może poprosić mailem o usunięcie zgłoszenia
  (office@admin-stack.com); podaj ten adres w formularzu.

Uprawnienie do lokalizacji: tylko na pierwszym planie, więc bez osobnego formularza
o lokalizacji w tle.

## 4. Listing

Teksty i kolejność zrzutów: [`store-listing.md`](store-listing.md).

## 5. Testy przed produkcją

Testowanie wewnętrzne, instalacja z linku na prawdziwym telefonie. Sprawdź:

- pierwsze uruchomienie bez internetu (lista z kopii w APK),
- zgoda na lokalizację: „tylko przybliżona” też ma posortować listę,
- mapa: kafelki, klastry, dotknięcie pinezki, „Prowadź” (Google Maps i inna apka),
- „Zgłoś poprawkę” otwiera pocztę z wypełnionym tematem i treścią,
- dodanie wybiegu, „Byłeś tu?” i „Moje zgłoszenia” z prawdziwym workerem (MODERACJA.md),
- build release (R8): czy lista nie jest pusta — to typowy objaw źle skonfigurowanego R8.

Konta osobiste założone po listopadzie 2023 potrzebują 12 testerów przez 14 dni testów
zamkniętych przed produkcją; konto firmowe nie.
