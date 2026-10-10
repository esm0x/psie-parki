package com.adminstack.psieparki.data

import java.util.Locale

/**
 * The e-mails behind "Zgłoś poprawkę" and "Zgłoś brakujący wybieg". There is no server
 * and no account, so a report is a pre-filled message the user sends from their own
 * mail app. It carries the park's id and coordinates, which is everything needed to
 * find it in OSM, and a checklist so people answer the questions that matter.
 */
object Report {
    const val EMAIL = "office@admin-stack.com"

    data class Mail(val to: String, val subject: String, val body: String)

    fun correction(park: Park, appVersion: String): Mail = Mail(
        to = EMAIL,
        subject = "Psie Parki: poprawka – " + listOfNotNull(park.title, park.place).joinToString(", "),
        body = buildString {
            appendLine("Co jest nie tak? (zaznacz i dopisz)")
            appendLine()
            appendLine("[ ] wybieg nie istnieje / jest zamknięty")
            appendLine("[ ] inna nazwa:")
            appendLine("[ ] ogrodzenie (tak/nie):")
            appendLine("[ ] oświetlenie (tak/nie):")
            appendLine("[ ] woda dla psów (tak/nie):")
            appendLine("[ ] sprzęt do agility (tak/nie):")
            appendLine("[ ] osobna część dla małych psów (tak/nie):")
            appendLine("[ ] coś innego:")
            appendLine()
            appendLine("—")
            appendLine("Wybieg: ${park.title}")
            park.place?.let { appendLine("Miejsce: $it") }
            appendLine("Id: ${park.id}")
            appendLine("Współrzędne: ${coords(park.lat, park.lon)}")
            park.osmUrl?.let { appendLine("OSM: $it") }
            appendLine("Wersja aplikacji: $appVersion")
        },
    )

    fun missing(near: LatLon?, appVersion: String): Mail = Mail(
        to = EMAIL,
        subject = "Psie Parki: brakujący wybieg",
        body = buildString {
            appendLine("Gdzie jest wybieg? (adres, ulica albo opis dojścia)")
            appendLine()
            appendLine()
            appendLine("Nazwa (jeśli ma):")
            appendLine("Ogrodzony (tak/nie):")
            appendLine("Oświetlony (tak/nie):")
            appendLine("Woda dla psów (tak/nie):")
            appendLine("Sprzęt do agility (tak/nie):")
            appendLine()
            appendLine("Zdjęcie bardzo pomaga — możesz je dołączyć do tej wiadomości.")
            appendLine()
            appendLine("—")
            near?.let { appendLine("Okolica (z aplikacji): ${coords(it.lat, it.lon)}") }
            appendLine("Wersja aplikacji: $appVersion")
        },
    )

    private fun coords(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)
}
