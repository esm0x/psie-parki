package com.adminstack.psieparki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.adminstack.psieparki.data.Report
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AboutActions(
    val onAddPark: () -> Unit = {},
    val onMySubmissions: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onMail: () -> Unit = {},
)

const val PRIVACY_URL = "https://github.com/esm0x/psie-parki/blob/main/PRIVACY.md"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(parkCount: Int, osmBase: String?, refreshing: Boolean, version: String, actions: AboutActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Więcej") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Psie Parki", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Wybiegi dla psów w całej Polsce: najbliższe, na mapie, z tym, co na nich jest. " +
                            "Bez reklam, bez konta i bez zbierania danych.",
                    )
                    Text(
                        "${countLabel(parkCount)} w bazie" + (osmBase?.let { " · stan OSM z ${formatDate(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(Icons.Outlined.AddLocationAlt, "Dodaj wybieg", "Zaznacz go na mapie — zajmie to minutę", actions.onAddPark)
            Row(Icons.Outlined.Inbox, "Moje zgłoszenia", "Co się stało z tym, co wysłałeś", actions.onMySubmissions)
            Row(Icons.Outlined.Refresh, if (refreshing) "Odświeżam…" else "Odśwież listę wybiegów", "Lista aktualizuje się sama raz na dobę", actions.onRefresh)
            Row(Icons.Outlined.Email, "Kontakt", Report.EMAIL, actions.onMail)
            Row(Icons.Outlined.Policy, "Polityka prywatności", "Lokalizacja nie opuszcza telefonu") { actions.onOpenUrl(PRIVACY_URL) }

            Text("Skąd są dane", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Wybiegi pochodzą z OpenStreetMap — otwartej mapy, którą tworzą wolontariusze — i od " +
                    "użytkowników aplikacji. Zgłoszenia sprawdza automat: nazwy i uwagi pod kątem spamu " +
                    "i danych osobowych, miejsca pod kątem sensowności. Nowy wybieg jest „niepotwierdzony”, " +
                    "dopóki nie potwierdzi go druga osoba, a znika, gdy kilka osób zgłosi, że go nie ma.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Dane mapy © współtwórcy OpenStreetMap, licencja ODbL. Podkład mapy: OpenFreeMap, OpenMapTiles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { actions.onOpenUrl("https://www.openstreetmap.org/copyright") },
            )
            Text(
                "Aplikacja jest niezależnym projektem i nie jest związana z żadnym urzędem ani zarządcą wybiegów. " +
                    "Sprawdzaj regulamin na miejscu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Wersja $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Row(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** "2026-10-09T01:44:35Z" → "9 października 2026". */
fun formatDate(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("pl-PL")))
}.getOrDefault(iso.take(10))
