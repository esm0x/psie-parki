package com.adminstack.psieparki.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.adminstack.psieparki.data.Park
import com.adminstack.psieparki.data.Report
import java.util.Locale

/** Hands the park to whatever map app the user has: Google Maps, OsmAnd, Yanosik… */
fun navigateTo(context: Context, park: Park) = navigateTo(context, park.lat, park.lon, park.title)

fun navigateTo(context: Context, latitude: Double, longitude: Double, title: String) {
    val lat = String.format(Locale.ROOT, "%.6f", latitude)
    val lon = String.format(Locale.ROOT, "%.6f", longitude)
    val label = Uri.encode(title)
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)"))
    try {
        context.startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        // No app takes geo: URIs (rare, but happens on stripped-down phones): the browser
        // version of Google Maps routes just as well.
        openUrl(context, "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Brak aplikacji, która otworzy ten adres", Toast.LENGTH_SHORT).show()
    }
}

fun sendMail(context: Context, mail: Report.Mail) {
    // ACTION_SENDTO with mailto: is the one form that offers only mail apps; the
    // subject and body ride in the URI, where every client finds them.
    val uri = Uri.parse(
        "mailto:${mail.to}?subject=${Uri.encode(mail.subject)}&body=${Uri.encode(mail.body)}",
    )
    val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        putExtra(Intent.EXTRA_TEXT, mail.body)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Napisz na ${mail.to}", Toast.LENGTH_LONG).show()
    }
}

/** Opens the dialer with the number typed in; calling stays the person's own tap. */
fun dial(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, uri.removePrefix("tel:"), Toast.LENGTH_LONG).show()
    }
}

/** "Górka dla Burka — wybieg dla psów, Rybnik…" with a map link anyone can open. */
fun sharePark(context: Context, park: Park) {
    val lat = String.format(Locale.ROOT, "%.5f", park.lat)
    val lon = String.format(Locale.ROOT, "%.5f", park.lon)
    val text = buildString {
        append(park.title)
        if (park.name != null) append(" — wybieg dla psów")
        park.place?.let { append(", ").append(it) }
        append("\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon")
        append("\n\nZnalezione w aplikacji Psie Parki")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Udostępnij wybieg"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Brak aplikacji do udostępniania", Toast.LENGTH_SHORT).show()
    }
}
