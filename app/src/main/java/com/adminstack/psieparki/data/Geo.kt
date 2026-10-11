package com.adminstack.psieparki.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Great-circle distance in metres. Straight line, not by road, and the UI says so. */
fun distanceMeters(a: LatLon, b: LatLon): Double {
    val r = 6_371_008.8
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun Park.distanceFrom(origin: LatLon): Double = distanceMeters(origin, LatLon(lat, lon))

/** "350 m", "2,4 km", "38 km": Polish decimal comma, no false precision far away. */
fun formatDistance(meters: Double): String = when {
    meters < 950 -> "${(meters / 10).roundToInt() * 10} m"
    meters < 9_950 -> String.format(Locale.forLanguageTag("pl-PL"), "%.1f km", meters / 1000)
    else -> "${(meters / 1000).roundToInt()} km"
}

/** "1 200 m²", rounded so a hand-drawn polygon does not look surveyed. */
fun formatArea(m2: Int): String {
    val rounded = when {
        m2 < 1_000 -> (m2 / 10.0).roundToInt() * 10
        m2 < 10_000 -> (m2 / 100.0).roundToInt() * 100
        else -> (m2 / 1000.0).roundToInt() * 1000
    }
    if (rounded >= 10_000) {
        val ha = rounded / 10_000.0
        return String.format(Locale.forLanguageTag("pl-PL"), "%.1f ha", ha).replace(",0 ha", " ha")
    }
    return "%,d m²".format(Locale.forLanguageTag("pl-PL"), rounded).replace(' ', ' ')
}

/**
 * Lower case without diacritics, so "lodz" finds Łódź and "zory" finds Żory. Ł is not a
 * combining character in Unicode, so NFD alone would leave it behind.
 */
fun foldForSearch(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('ł', 'l')
