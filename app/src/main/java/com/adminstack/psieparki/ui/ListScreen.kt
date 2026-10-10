package com.adminstack.psieparki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.RankedPark
import com.adminstack.psieparki.data.formatDistance

/** Callbacks of the list, so screenshots and tests can drive it without a ViewModel. */
data class ListActions(
    val onText: (String) -> Unit = {},
    val onToggle: (Feature) -> Unit = {},
    val onLocate: () -> Unit = {},
    val onPickPlace: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onReportMissing: () -> Unit = {},
)

/** How many rows are drawn at once; nobody scrolls past the nearest hundred parks. */
private const val SHOWN = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(ui: ListUi, actions: ListActions) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Psie Parki", fontWeight = FontWeight.SemiBold)
                    Text(
                        ui.origin?.let { "Najbliżej: ${it.label}" } ?: "Wybiegi dla psów w Polsce",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }, actions = {
                IconButton(onClick = actions.onLocate) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Moja lokalizacja")
                }
                IconButton(onClick = actions.onPickPlace) {
                    Icon(Icons.Outlined.LocationCity, contentDescription = "Wybierz miejscowość")
                }
            })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SearchField(ui.query.text, actions.onText) }
            item { FilterRow(ui.query.features, actions.onToggle) }

            if (ui.origin == null) {
                item { OriginPrompt(ui, actions) }
            } else if (ui.locating) {
                item { Hint("Szukam Twojej lokalizacji…", progress = true) }
            }
            if (ui.locationFailed) {
                item { Hint("Nie udało się ustalić lokalizacji. Włącz ją w telefonie albo wybierz miejscowość.") }
            }

            when {
                ui.parks.loading && ui.parks.parks.isEmpty() -> item { Hint("Wczytuję wybiegi…", progress = true) }
                ui.parks.error != null -> item { Hint(ui.parks.error) }
                ui.ranked.isEmpty() -> item {
                    Hint(
                        if (ui.query.features.isNotEmpty()) {
                            "Żaden wybieg nie spełnia wszystkich filtrów. Wiele wybiegów nie ma jeszcze opisanych udogodnień — spróbuj z mniejszą liczbą filtrów."
                        } else {
                            "Nic nie pasuje do „${ui.query.text}”."
                        },
                    )
                }
                else -> {
                    item {
                        Text(
                            countLabel(ui.ranked.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(ui.ranked.take(SHOWN), key = { it.park.id }) { ParkRow(it) { actions.onOpen(it.park.id) } }
                    if (ui.ranked.size > SHOWN) {
                        item { Hint("Pokazuję $SHOWN najbliższych. Wpisz miejscowość, żeby zawęzić listę.") }
                    }
                }
            }

            item {
                OutlinedButton(onClick = actions.onReportMissing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Brakuje wybiegu? Zgłoś go")
                }
            }
        }
    }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Miasto, dzielnica lub nazwa") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onText("") }) { Icon(Icons.Outlined.Clear, contentDescription = "Wyczyść") }
            }
        },
    )
}

@Composable
private fun FilterRow(selected: Set<Feature>, onToggle: (Feature) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Feature.entries) { f ->
            FilterChip(
                selected = f in selected,
                onClick = { onToggle(f) },
                label = { Text(f.label) },
                leadingIcon = { Icon(f.icon, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
    }
}

@Composable
private fun OriginPrompt(ui: ListUi, actions: ListActions) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gdzie szukamy?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pokażę wybiegi od najbliższego. Lokalizacja zostaje w telefonie — służy tylko do liczenia odległości.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.onLocate, enabled = !ui.locating) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Moja lokalizacja")
                }
                TextButton(onClick = actions.onPickPlace) { Text("Wybierz miejscowość") }
            }
        }
    }
}

@Composable
fun ParkRow(item: RankedPark, onClick: () -> Unit) {
    val park = item.park
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    park.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    item.distance?.let(::formatDistance),
                    park.place,
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val present = Feature.entries.filter { it.test(park) == true }
                if (present.isNotEmpty()) FeaturePills(present, Modifier.padding(top = 2.dp))
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Hint(text: String, progress: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress) {
            Box(Modifier.size(20.dp)) { CircularProgressIndicator(strokeWidth = 2.dp) }
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Polish plurals: 1 wybieg, 2–4 wybiegi, 5–21 wybiegów, 22–24 wybiegi… */
fun countLabel(n: Int): String {
    val word = when {
        n == 1 -> "wybieg"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "wybiegi"
        else -> "wybiegów"
    }
    return "$n $word"
}

