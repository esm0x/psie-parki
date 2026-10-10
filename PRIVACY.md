# Polityka prywatności — Psie Parki

Obowiązuje od: 10 października 2026 r.

Aplikacja **Psie Parki** (Android, `com.adminstack.psieparki`) jest niezależnym projektem
admin-stack. Nie jest związana z żadnym urzędem, gminą ani zarządcą wybiegów.

## W skrócie

- Nie zbieramy żadnych danych osobowych. Nie mamy serwera, na który mogłyby trafić.
- Nie ma kont, logowania, reklam ani narzędzi analitycznych.
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
- **tiles.openfreemap.org** (OpenFreeMap) — pobiera podkład mapy dla oglądanego fragmentu,
  gdy otworzysz zakładkę Mapa. Jak każdy serwer, widzi adres IP i fragment mapy, o który
  prosi telefon; nie przekazujemy mu lokalizacji.

Dostawcy tych usług mogą przetwarzać adres IP zgodnie z własnymi zasadami.

## Zgłoszenia

Przyciski „Zgłoś poprawkę” i „Zgłoś brakujący wybieg” otwierają Twoją aplikację pocztową
z gotową wiadomością. Wysyłasz ją sam; zawiera to, co widzisz przed wysłaniem (dane
wybiegu, wersję aplikacji, a przy brakującym wybiegu — współrzędne okolicy, jeśli
wcześniej udostępniłeś lokalizację; możesz je usunąć). Wiadomości używamy wyłącznie do
poprawienia danych i nie przekazujemy ich dalej.

## Dane o wybiegach

Pochodzą z OpenStreetMap (© współtwórcy OpenStreetMap, licencja ODbL) i ze zgłoszeń.
Nie dotyczą osób.

## Kontakt

office@admin-stack.com
