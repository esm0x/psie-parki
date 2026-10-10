package com.adminstack.psieparki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.Park
import com.adminstack.psieparki.data.formatArea
import com.adminstack.psieparki.data.formatDistance

data class DetailActions(
    val onBack: () -> Unit = {},
    val onNavigate: (Park) -> Unit = {},
    val onShowOnMap: (Park) -> Unit = {},
    val onReport: (Park) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    park: Park?,
    distance: Double?,
    actions: DetailActions,
    /** "Byłeś tu?": absent when the app has no submissions worker to send to. */
    contribute: @Composable (Park) -> Unit = {},
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(park?.title ?: "Wybieg", maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wstecz")
                }
            },
        )
    }) { padding ->
        if (park == null) {
            Column(Modifier.padding(padding).padding(16.dp)) { Hint("Tego wybiegu nie ma już na liście.") }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(park.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (!park.confirmed) UnconfirmedTag()
                val where = listOfNotNull(park.place, park.region?.let { "woj. $it" }).joinToString(" · ")
                if (where.isNotEmpty()) Text(where, color = MaterialTheme.colorScheme.onSurfaceVariant)
                distance?.let {
                    Text(
                        "${formatDistance(it)} w linii prostej",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onNavigate(park) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Directions, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prowadź")
                }
                OutlinedButton(onClick = { actions.onShowOnMap(park) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Map, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Na mapie")
                }
            }

            if (park.customersOnly) {
                Note("Tylko dla klientów — to wybieg przy hotelu, sklepie albo szkole dla psów.")
            }
            park.note?.let { Note(it) }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Feature.entries.forEachIndexed { i, f ->
                        FeatureLine(f, f.test(park))
                        if (i < Feature.entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            if (Feature.entries.any { it.test(park) == null }) {
                Text(
                    "„Brak danych” znaczy, że nikt jeszcze tego nie opisał.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            contribute(park)

            val facts = listOfNotNull(
                park.areaM2?.let { "Powierzchnia" to "ok. ${formatArea(it)}" },
                park.surface?.let { "Nawierzchnia" to it },
                park.openingHours?.let { "Godziny" to it },
                park.benches?.let { "Ławki" to if (it) "są" else "brak" },
                park.bins?.let { "Kosze na odchody" to if (it) "są" else "brak" },
                park.operator?.let { "Zarządca" to it },
            )
            if (facts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    facts.forEach { (k, v) ->
                        Row {
                            Text(k, Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, Modifier.weight(0.58f))
                        }
                    }
                }
            }
            park.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            park.website?.let { url ->
                TextButton(onClick = { actions.onOpenUrl(url) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Strona wybiegu")
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = { actions.onReport(park) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Zgłoś poprawkę")
            }
            Text(
                when {
                    park.isFromOsm -> "Dane: © współtwórcy OpenStreetMap (ODbL)."
                    park.isFromApp -> "Wybieg dodany przez użytkownika aplikacji."
                    else -> "Wybieg dodany na podstawie zgłoszenia."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            park.osmUrl?.let { url ->
                TextButton(onClick = { actions.onOpenUrl(url) }) { Text("Zobacz lub popraw w OpenStreetMap") }
            }
        }
    }
}

@Composable
private fun FeatureLine(feature: Feature, value: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(feature.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(feature.longLabel, Modifier.weight(1f).padding(start = 12.dp))
        val (icon, text, tint) = when (value) {
            true -> Triple(Icons.Outlined.Check, "tak", MaterialTheme.colorScheme.primary)
            false -> Triple(Icons.Outlined.Close, "nie", MaterialTheme.colorScheme.error)
            null -> Triple(Icons.AutoMirrored.Outlined.HelpOutline, "brak danych", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, color = tint, style = MaterialTheme.typography.bodyMedium)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

private val Feature.longLabel: String
    get() = when (this) {
        Feature.FENCED -> "Ogrodzenie"
        Feature.LIT -> "Oświetlenie"
        Feature.WATER -> "Woda dla psów"
        Feature.AGILITY -> "Sprzęt do agility"
        Feature.SMALL_DOGS -> "Część dla małych psów"
    }

@Composable
private fun Note(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(text, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

