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

Wybraną miejscowość, włączone filtry i ulubione wybiegi aplikacja zapamiętuje lokalnie
na telefonie.
Odinstalowanie aplikacji usuwa je.

## Połączenia z internetem

Aplikacja łączy się z:

- **raw.githubusercontent.com** (GitHub) — pobiera listę wybiegów oraz weterynarzy
  i schronisk, raz na dobę. Zapytanie nie zawiera żadnych danych o Tobie ani o Twojej
  lokalizacji.
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
- nazwę i cechy (ogrodzenie, oświetlenie…), jeśli je podasz,
- losowy identyfikator instalacji aplikacji i jej wersję.

Serwer nie zapisuje adresu IP ani identyfikatora instalacji. Zamienia identyfikator
i sieć, z której przyszło zgłoszenie (bez ostatniej części adresu IP), w jednokierunkowe
skróty (HMAC z tajnym kluczem). Po skrótach da się odróżnić dwie osoby potwierdzające ten
sam wybieg, ale nie da się ustalić, kim są ani skąd piszą. Skróty trafiają do zgłoszenia;
liczniki dziennych limitów zgłoszeń wygasają po dwóch dniach.

Nazwę przed zapisaniem sprawdza automatyczny filtr na naszym serwerze (bez udziału
ludzi ani zewnętrznych usług), żeby odsiać reklamy, wulgaryzmy i dane kontaktowe. Nazwa
odrzucona nie jest nigdzie zapisywana ani publikowana.

Przyjęte zgłoszenie staje się **publicznym** wpisem (issue) w repozytorium
github.com/esm0x/psie-parki — razem z miejscem wybiegu, nazwą, cechami
i skrótami opisanymi wyżej. Z tego repozytorium powstaje lista wybiegów w aplikacji.
Nie wpisuj więc w nazwie niczego, czego nie chcesz publikować.

Status Twoich zgłoszeń aplikacja zapamiętuje na telefonie (Więcej → Moje zgłoszenia).

## Weterynarze, telefon i udostępnianie

„Zadzwoń” otwiera systemową aplikację telefonu z wpisanym numerem — dzwonisz sam,
aplikacja nie łączy rozmów i nie widzi ich. „Prowadź” przekazuje współrzędne miejsca
Twojej aplikacji z mapami, a „Udostępnij” — nazwę i link do mapy wybranej przez Ciebie
aplikacji. Nic z tego nie trafia do nas.

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
