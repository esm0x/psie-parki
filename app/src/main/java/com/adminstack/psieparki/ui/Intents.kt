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
fun navigateTo(context: Context, park: Park) {
    val lat = String.format(Locale.ROOT, "%.6f", park.lat)
    val lon = String.format(Locale.ROOT, "%.6f", park.lon)
    val label = Uri.encode(park.title)
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
