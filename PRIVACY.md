# Polityka prywatności — Psie Parki

Obowiązuje od: 10 października 2026 r. (wersja z dodawaniem wybiegów w aplikacji)

Aplikacja **Psie Parki** (Android, `com.adminstack.psieparki`) jest niezależnym projektem
admin-stack. Nie jest związana z żadnym urzędem, gminą ani zarządcą wybiegów.

## W skrócie

- Nie zbieramy danych osobowych. Nie ma kont, logowania, reklam ani narzędzi analitycznych.
- Jeśli dodasz lub potwierdzisz wybieg, wysyłamy tylko dane o wybiegu — opis niżej.
- Lokalizacja służy tylko do posortowania wybiegów według odległości. Liczymy ją na
  telefonie, nigdzie jej nie wysyłamy i nie zapisujemy.

## Lokalizacja

Jeśli na to pozwolisz, aplikacja jednorazowo odczytuje przybliżone lub dokładne
położenie telefonu z systemu Android, żeby policzyć odległość do wybiegów. Pozycja nie
opuszcza urządzenia i nie jest przechowywana. Możesz w każdej chwili cofnąć zgodę
w ustawieniach Androida; aplikacja działa wtedy po wyborze miejscowości.

Wybraną miejscowość i włączone filtry aplikacja zapamiętuje lokalnie na telefonie.
Odinstalowanie aplikacji usuwa je.

## Połączenia z internetem

Aplikacja łączy się z:

- **raw.githubusercontent.com** (GitHub) — pobiera listę wybiegów, raz na dobę. Zapytanie
  nie zawiera żadnych danych o Tobie ani o Twojej lokalizacji.
- **serwer zgłoszeń** (Cloudflare Workers) — tylko gdy coś zgłaszasz; opis niżej.
- **tiles.openfreemap.org** (OpenFreeMap) — pobiera podkład mapy dla oglądanego fragmentu,
  gdy otworzysz zakładkę Mapa. Jak każdy serwer, widzi adres IP i fragment mapy, o który
  prosi telefon; nie przekazujemy mu lokalizacji.

Dostawcy tych usług mogą przetwarzać adres IP zgodnie z własnymi zasadami.

## Dodawanie i potwierdzanie wybiegów

Gdy dodajesz wybieg, potwierdzasz go, zgłaszasz, że go nie ma, albo odpowiadasz na
pytanie o jego wyposażenie, aplikacja wysyła na nasz serwer zgłoszeń (Cloudflare
Workers) tylko to, co dotyczy wybiegu:

- rodzaj zgłoszenia i identyfikator wybiegu albo wskazane przez Ciebie na mapie miejsce
  wybiegu (to miejsce wybiegu, nie Twoja lokalizacja),
- nazwę, cechy (ogrodzenie, oświetlenie…) i uwagi, jeśli je wpiszesz,
- losowy identyfikator instalacji aplikacji i jej wersję.

Serwer nie zapisuje adresu IP ani identyfikatora instalacji. Zamienia identyfikator
i sieć, z której przyszło zgłoszenie (bez ostatniej części adresu IP), w jednokierunkowe
skróty (HMAC z tajnym kluczem). Po skrótach da się odróżnić dwie osoby potwierdzające ten
sam wybieg, ale nie da się ustalić, kim są ani skąd piszą. Skróty trafiają do zgłoszenia;
liczniki dziennych limitów zgłoszeń wygasają po dwóch dniach.

Nazwę i uwagi przed zapisaniem ocenia model językowy Claude firmy Anthropic, żeby odsiać
spam, reklamy, wulgaryzmy i dane osobowe. Anthropic przetwarza ten tekst jako nasz
dostawca usługi, na swoich warunkach dla API. Treść odrzucona nie jest nigdzie
publikowana.

Przyjęte zgłoszenie staje się **publicznym** wpisem (issue) w repozytorium
github.com/esm0x/psie-parki — razem z miejscem wybiegu, nazwą, cechami, uwagami
i skrótami opisanymi wyżej. Z tego repozytorium powstaje lista wybiegów w aplikacji.
Nie wpisuj więc w uwagach niczego, czego nie chcesz publikować.

Status Twoich zgłoszeń aplikacja zapamiętuje na telefonie (Więcej → Moje zgłoszenia).

## Zgłoszenia mailem

„Zgłoś poprawkę” otwiera Twoją aplikację pocztową z gotową wiadomością. Wysyłasz ją sam;
zawiera to, co widzisz przed wysłaniem. Wiadomości używamy wyłącznie do poprawienia
danych i nie przekazujemy ich dalej.

## Dane o wybiegach

Pochodzą z OpenStreetMap (© współtwórcy OpenStreetMap, licencja ODbL) i od użytkowników
aplikacji.
Nie dotyczą osób.

## Kontakt

office@admin-stack.com
