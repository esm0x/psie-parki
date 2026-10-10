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
| Grupa odbiorców | **13+** wystarczy: treść to tylko mapa i opisy wybiegów, bez feedów z wiadomościami. Nie zaznaczaj dzieci (włącza Families Policy) |
| Aplikacja rządowa | Nie |

### Bezpieczeństwo danych

- **Zbiera dane?** → **Nie.** Lokalizacja jest przetwarzana tylko na urządzeniu
  (sortowanie po odległości) i nigdzie nie wysyłana — według definicji Google to nie
  jest „zbieranie”.
- **Udostępnia dane?** → **Nie.** Pobieranie listy wybiegów i kafelków mapy nie
  zawiera danych użytkownika.
- **Szyfrowanie w transmisji** → Tak (HTTPS).
- **Usuwanie danych** → nie dotyczy (brak serwera; odinstalowanie usuwa wszystko).

Uprawnienie do lokalizacji wymaga w Play Console tylko deklaracji w formularzu
Bezpieczeństwa danych (jak wyżej); lokalizacja w tle **nie** jest używana, więc nie
ma osobnego formularza.

## 4. Listing

Teksty i kolejność zrzutów: [`store-listing.md`](store-listing.md).

## 5. Testy przed produkcją

Testowanie wewnętrzne, instalacja z linku na prawdziwym telefonie. Sprawdź:

- pierwsze uruchomienie bez internetu (lista z kopii w APK),
- zgoda na lokalizację: „tylko przybliżona” też ma posortować listę,
- mapa: kafelki, klastry, dotknięcie pinezki, „Prowadź” (Google Maps i inna apka),
- „Zgłoś poprawkę” otwiera pocztę z wypełnionym tematem i treścią,
- build release (R8): czy lista nie jest pusta — to typowy objaw źle skonfigurowanego R8.

Konta osobiste założone po listopadzie 2023 potrzebują 12 testerów przez 14 dni testów
zamkniętych przed produkcją; konto firmowe nie.
