package com.adminstack.psieparki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Locality
import com.adminstack.psieparki.data.foldForSearch

/**
 * The alternative to sharing a location: pick a place that has dog parks. Only places
 * with at least one park are listed, so every choice leads somewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerScreen(places: List<Locality>, onPick: (Locality) -> Unit, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val folded = remember(places) { places.map { foldForSearch(it.label) } }
    val needle = foldForSearch(text.trim())
    // Prefix matches first: "rybnik" should put Rybnik above Nowy Rybnik.
    val shown = places.indices
        .filter { needle.isEmpty() || folded[it].contains(needle) }
        .sortedBy { if (needle.isNotEmpty() && folded[it].startsWith(needle)) 0 else 1 }
        .map { places[it] }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Wybierz miejscowość") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wstecz") }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Wpisz nazwę, np. Rybnik") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.label }) { place ->
                    ListItem(
                        headlineContent = { Text(place.label) },
                        supportingContent = {
                            Text(countLabel(place.parkCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        modifier = Modifier.clickable { onPick(place) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
