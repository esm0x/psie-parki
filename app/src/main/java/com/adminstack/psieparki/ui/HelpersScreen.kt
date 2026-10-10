package com.adminstack.psieparki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Helper
import com.adminstack.psieparki.data.HelperFilter
import com.adminstack.psieparki.data.LatLon
import com.adminstack.psieparki.data.OpeningHours
import com.adminstack.psieparki.data.describe
import com.adminstack.psieparki.data.distanceMeters
import com.adminstack.psieparki.data.formatDistance
import java.time.LocalDateTime

data class HelperActions(
    val onCall: (Helper) -> Unit = {},
    val onNavigate: (Helper) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onLocate: () -> Unit = {},
    val onPickPlace: () -> Unit = {},
)

private const val SHOWN = 60

/**
 * "Weterynarz": the nearest vets, which are open now, and one tap to call. The thing a
 * dog owner needs on the worst evening of the year, and no dog-park app has it.
 * Shelters are the second tab: adopting is the other reason people look for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpersScreen(
    places: List<Helper>,
    origin: LatLon?,
    originLabel: String?,
    actions: HelperActions,
    now: LocalDateTime = LocalDateTime.now(),
) {
    var vets by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf<HelperFilter?>(null) }
    val shown = remember(places, origin, vets, filter, now.hour, now.minute / 10) {
        places.asSequence()
            .filter { it.isVet == vets }
            .filter { h ->
                when (filter) {
                    null -> true
                    HelperFilter.ALL_DAY -> h.allDay
                    HelperFilter.OPEN_NOW -> h.hours?.status(now) is OpeningHours.Status.Open
                }
            }
            .map { it to origin?.let { o -> distanceMeters(o, LatLon(it.lat, it.lon)) } }
            .sortedBy { it.second ?: 0.0 }
            .take(SHOWN)
            .toList()
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(if (vets) "Weterynarz w pobliżu" else "Schroniska", fontWeight = FontWeight.SemiBold)
                Text(
                    originLabel?.let { "Najbliżej: $it" } ?: "Wybierz miejsce, żeby zobaczyć najbliższe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = vets, onClick = { vets = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Weterynarze") }
                    SegmentedButton(selected = !vets, onClick = { vets = false; filter = null }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Schroniska") }
                }
            }
            if (vets) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HelperFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = if (filter == f) null else f }, label = { Text(f.label) })
                        }
                    }
                }
                item {
                    Text(
                        "W nagłym wypadku zadzwoń przed wyjazdem. Godziny i telefony pochodzą z OpenStreetMap i mogą być nieaktualne.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        "Szukasz psa? Wiele schronisk ma strony z psami do adopcji — zadzwoń albo zajrzyj na ich stronę.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (origin == null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = actions.onLocate) { Text("Moja lokalizacja") }
                        TextButton(onClick = actions.onPickPlace) { Text("Wybierz miejscowość") }
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Hint(
                        if (filter == HelperFilter.OPEN_NOW) {
                            "Nie znamy żadnego gabinetu otwartego teraz w pobliżu. Wiele gabinetów nie ma w mapie godzin — sprawdź listę bez filtra i zadzwoń."
                        } else {
                            "Nic tu nie ma."
                        },
                    )
                }
            }
            items(shown, key = { it.first.id }) { (h, d) -> HelperCard(h, d, now, actions) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelperCard(h: Helper, distance: Double?, now: LocalDateTime, actions: HelperActions) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(h.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(distance?.let(::formatDistance), h.place).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            val status = h.hours?.status(now)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, Modifier.size(16.dp),
                    tint = when (status) {
                        is OpeningHours.Status.Open -> MaterialTheme.colorScheme.primary
                        is OpeningHours.Status.Closed -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        status != null -> describe(status, now)
                        h.openingHours != null -> "Godziny: ${h.openingHours}"
                        h.dialUri != null -> "Godziny nieznane — zadzwoń"
                        else -> "Godziny nieznane"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = when (status) {
                        is OpeningHours.Status.Open -> MaterialTheme.colorScheme.primary
                        is OpeningHours.Status.Closed -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // Three buttons do not fit a 360 dp phone in one row: they wrap instead of
            // squeezing the last one to nothing.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (h.dialUri != null) {
                    FilledTonalButton(onClick = { actions.onCall(h) }) {
                        Icon(Icons.Outlined.Call, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zadzwoń")
                    }
                }
                OutlinedButton(onClick = { actions.onNavigate(h) }) {
                    Icon(Icons.Outlined.Directions, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prowadź")
                }
                h.website?.let { url ->
                    TextButton(onClick = { actions.onOpenUrl(url) }) {
                        Icon(Icons.Outlined.Language, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Strona")
                    }
                }
            }
        }
    }
}
